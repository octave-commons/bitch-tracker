(ns bitch-tracker.plugin
  "BetterDiscord plugin lifecycle and Discord event handling."
  (:require [bitch-tracker.plugin.socket-client :as socket]
            [bitch-tracker.shared.constants :as c]
            [bitch-tracker.shared.events :as events]
            [bitch-tracker.shared.util :as u]
            [clojure.string :as str]))

;; ─────────────────────────────────────────────────────────────
;; BetterDiscord interop
;; ─────────────────────────────────────────────────────────────

(defn- global-get [k]
  (aget js/globalThis k))

(defn- bd-api []
  (global-get "BdApi"))

(defn- bd-webpack []
  (u/jget (bd-api) "Webpack"))

(defn- meta-field [plugin-meta k fallback]
  (let [value (when plugin-meta (aget plugin-meta k))]
    (if (u/present-string? value) value fallback)))

(defn- log! [level plugin-meta & xs]
  (let [plugin-name (meta-field plugin-meta "name" c/plugin-name)
        logger (u/jget (bd-api) "Logger")
        logger-fn (u/jget logger level)
        console-fn (or (u/jget js/console level) (.-log js/console))]
    (if logger-fn
      (.apply logger-fn logger (into-array (cons plugin-name xs)))
      (.apply console-fn js/console (into-array (cons (str "[" plugin-name "]") xs))))))

(defn- toast! [message toast-type]
  (when-let [show-toast (u/jget (bd-api) "UI" "showToast")]
    (.call show-toast (u/jget (bd-api) "UI") message #js {:type toast-type})))

(defn- bd-data-load [plugin setting-key]
  (u/jcall (u/jget (bd-api) "Data") "load" plugin setting-key))

(defn- bd-data-save! [plugin setting-key value]
  (u/jcall (u/jget (bd-api) "Data") "save" plugin setting-key value))

(defn- setting [plugin setting-key fallback]
  (let [value (bd-data-load plugin setting-key)]
    (if (undefined? value) fallback value)))

(defn- save-setting! [plugin setting-key value]
  (bd-data-save! plugin setting-key value))

(defn ^:async sleep-ms
  "Returns a promise that resolves after ms milliseconds."
  [ms]
  (await
   (js/Promise.
    (fn [resolve-fn _reject]
      (js/setTimeout resolve-fn ms)))))

;; ─────────────────────────────────────────────────────────────
;; State
;; ─────────────────────────────────────────────────────────────

(defn- make-state []
  #js {:started false
       :startedAt nil
       :stoppedAt nil
       :dispatcher nil
       :channelStore nil
       :guildStore nil
       :userStore nil
       :messageStore nil
       :socket-state nil
       :bot-url c/default-bot-url
       :bitchCounts (js/Map.)
       :bitchMessages (js/Map.)
       :labeledMessages (js/Set.)
       :runningBackfill false})

(defn- state-get [state k]
  (aget state k))

(defn- state-set! [state k v]
  (aset state k v)
  v)

(defn- load-modules! [state]
  (let [webpack (bd-webpack)
        get-module (u/jget webpack "getModule")
        get-store (u/jget webpack "getStore")
        dispatcher (when get-module (.call get-module webpack (fn [m] (and (u/jget m "dispatch") (u/jget m "subscribe") (u/jget m "unsubscribe"))) #js {:searchExports true}))
        channel-store (or (when get-store (.call get-store webpack "ChannelStore"))
                          (when get-module (.call get-module webpack (fn [m] (and (u/jget m "getChannel") (u/jget m "getDMFromUserId"))) #js {:searchExports true})))
        guild-store (or (when get-store (.call get-store webpack "GuildStore"))
                        (when get-module (.call get-module webpack (fn [m] (and (u/jget m "getGuild") (u/jget m "getGuilds"))) #js {:searchExports true})))
        user-store (or (when get-store (.call get-store webpack "UserStore"))
                       (when get-module (.call get-module webpack (fn [m] (and (u/jget m "getCurrentUser") (u/jget m "getUser"))) #js {:searchExports true})))
        message-store (or (when get-store (.call get-store webpack "MessageStore"))
                          (when get-module (.call get-module webpack (fn [m] (and (u/jget m "getMessage") (u/jget m "getMessages"))) #js {:searchExports true})))]
    (state-set! state "dispatcher" dispatcher)
    (state-set! state "channelStore" channel-store)
    (state-set! state "guildStore" guild-store)
    (state-set! state "userStore" user-store)
    (state-set! state "messageStore" message-store)
    state))

(defn- channel-for [state channel-id]
  (u/jcall (state-get state "channelStore") "getChannel" channel-id))

(defn- guild-id-for [_state message channel fallback]
  (str (or fallback
           (u/jget message "guild_id")
           (u/jget message "guildId")
           (u/jget channel "guild_id")
           (u/jcall channel "getGuildId")
           "")))

(defn- current-user-id [state]
  (str (or (u/jget (u/jcall (state-get state "userStore") "getCurrentUser") "id") "")))

(defn- current-user-name [state]
  (str (or (u/jget (u/jcall (state-get state "userStore") "getCurrentUser") "username")
           (u/jget (u/jcall (state-get state "userStore") "getCurrentUser") "globalName")
           "")))

(defn- bot-config-set [state cfg-key]
  (let [cfg (socket/bot-config (state-get state "socket-state"))
        value (u/jget cfg cfg-key)]
    (if (instance? js/Set value)
      value
      (into #{} (map str) (u/js-values value)))))

;; ─────────────────────────────────────────────────────────────
;; Bot connection
;; ─────────────────────────────────────────────────────────────

(defn- bot-url [state]
  (let [saved (str (setting "BitchTracker" "botUrl" ""))]
    (if (u/present-string? saved)
      saved
      (state-get state "bot-url"))))

(defn- connect-to-bot! [state plugin-meta]
  (let [url (bot-url state)
        socket-state (socket/make-state)]
    (state-set! state "socket-state" socket-state)
    (state-set! state "bot-url" url)
    (socket/connect! socket-state url
                     #js {:userId (current-user-id state)
                          :username (current-user-name state)
                          :hostname (when (and (exists? js/window) (.-location js/window))
                                      (.. js/window -location -hostname))}
                     {:on-watch-alert (fn [data]
                                        (let [user-id (str (u/jget data "userId"))
                                              label-count (u/jget data "count")]
                                          (toast! (str "Moderation watch alert: " user-id " (" label-count " labels)") "warning")
                                          (log! "info" plugin-meta "Watch alert received" user-id label-count)))
                      :on-tracker-msg (fn [data]
                                        (log! "info" plugin-meta "Tracker message from bot" (u/jget data "reason")))
                      :on-status (fn [data]
                                   (log! "info" plugin-meta "Bot status" (u/jget data "status")))})
    (socket/request-config! socket-state)
    state))

(defn- disconnect-from-bot! [state]
  (when-let [socket-state (state-get state "socket-state")]
    (socket/disconnect! socket-state)
    (state-set! state "socket-state" nil))
  state)

;; ─────────────────────────────────────────────────────────────
;; Label state (local UI counters)
;; ─────────────────────────────────────────────────────────────

(defn- persist-label-state! [state]
  (let [counts (js/Array.from (.entries (state-get state "bitchCounts")))
        messages (.map (js/Array.from (.entries (state-get state "bitchMessages")))
                       (fn [entry]
                         #js [(aget entry 0) (js/Array.from (aget entry 1))]))
        labeled (js/Array.from (state-get state "labeledMessages"))]
    (bd-data-save! "BitchTracker" "bitchState"
                   #js {:counts counts :messages messages :labeled labeled})))

(defn- load-label-state! [state]
  (when-let [saved (bd-data-load "BitchTracker" "bitchState")]
    (when (u/jget saved "counts") (state-set! state "bitchCounts" (js/Map. (u/jget saved "counts"))))
    (when (u/jget saved "messages")
      (state-set! state "bitchMessages"
                  (js/Map. (.map (u/jget saved "messages") (fn [entry] #js [(aget entry 0) (js/Set. (aget entry 1))])))))
    (when (u/jget saved "labeled") (state-set! state "labeledMessages" (js/Set. (u/jget saved "labeled"))))))

(defn- add-label-message! [state user-id message-id]
  (when-not (.has (state-get state "bitchMessages") user-id)
    (.set (state-get state "bitchMessages") user-id (js/Set.)))
  (.add (.get (state-get state "bitchMessages") user-id) message-id)
  (.add (state-get state "labeledMessages") message-id))

(defn- remove-label-message! [state user-id message-id]
  (when-let [messages (.get (state-get state "bitchMessages") user-id)]
    (.delete messages message-id))
  (.delete (state-get state "labeledMessages") message-id))

;; ─────────────────────────────────────────────────────────────
;; Discord event handlers
;; ─────────────────────────────────────────────────────────────

(defn- on-message-create! [state payload]
  (let [message (or (u/jget payload "message") payload)
        message-id (u/jget message "id")
        channel-id (or (u/jget message "channel_id") (u/jget message "channelId"))]
    (when (and message-id channel-id (not= (u/jget message "state") "SENDING") (not= (u/jget message "type") 8))
      (let [channel (channel-for state channel-id)
            guild-id (guild-id-for state message channel nil)]
        ;; No self-filter: forward all authors, including the logged-in account.
        ;; Loop-safe — the bot ignores events authored by its own bot-user-id.
        (when-let [socket-state (state-get state "socket-state")]
          (socket/send-event! socket-state (events/message-to-event message channel guild-id (bot-config-set state "knownLabelUserIds"))))))))

(defn- handle-label-reaction! [state message-id channel-id reactor-user-id]
  (let [message (u/jcall (state-get state "messageStore") "getMessage" channel-id message-id)]
    (if-not message
      (log! "warn" nil "Could not find labeled message" message-id "in" channel-id)
      (let [aid (events/author-id message)]
        (when (u/present-string? aid)
          (add-label-message! state aid message-id)
          (let [counts (state-get state "bitchCounts")
                current (or (.get counts aid) 0)
                next-count (inc current)]
            (.set counts aid next-count)
            (persist-label-state! state)
            (when-let [socket-state (state-get state "socket-state")]
              (socket/send-label-added! socket-state
                                        #js {:userId aid
                                             :messageId message-id
                                             :reactorId reactor-user-id
                                             :message message
                                             :channel (channel-for state channel-id)
                                             :guildId (guild-id-for state message (channel-for state channel-id) nil)}))
            (when (and (>= next-count c/label-threshold) (< current c/label-threshold))
              (toast! (str "User tagged for moderation watch: " aid) "warning"))))))))

(defn- handle-label-reaction-remove! [state message-id channel-id reactor-user-id]
  (let [message (u/jcall (state-get state "messageStore") "getMessage" channel-id message-id)
        aid (events/author-id message)]
    (when (and message (u/present-string? aid) (.has (state-get state "labeledMessages") message-id))
      (remove-label-message! state aid message-id)
      (let [counts (state-get state "bitchCounts")
            current (or (.get counts aid) 0)]
        (when (> current 0) (.set counts aid (dec current))))
      (persist-label-state! state)
      (when-let [socket-state (state-get state "socket-state")]
        (socket/send-label-removed! socket-state
                                    #js {:userId aid
                                         :messageId message-id
                                         :reactorId reactor-user-id
                                         :guildId (guild-id-for state message (channel-for state channel-id) nil)})))))

(defn- on-reaction-add! [state payload]
  (let [message-id (str (or (u/jget payload "messageId") ""))
        channel-id (str (or (u/jget payload "channelId") ""))]
    (when (and (u/present-string? message-id) (u/present-string? channel-id))
      (let [channel (channel-for state channel-id)
            guild-id (str (or (u/jget payload "guildId") (u/jget channel "guild_id") (u/jcall channel "getGuildId") ""))
            emoji-name (str (or (u/jget payload "emoji" "name") ""))
            emoji-id (str (or (u/jget payload "emoji" "id") ""))
            emoji (if (u/present-string? emoji-name) emoji-name emoji-id)
            user-id (str (or (u/jget payload "userId") ""))]
        (when (events/label-emoji? emoji-name emoji)
          (handle-label-reaction! state message-id channel-id user-id))
        (when-let [socket-state (state-get state "socket-state")]
          (socket/send-event! socket-state (events/reaction-to-event payload channel guild-id emoji user-id nil)))))))

(defn- on-reaction-remove! [state payload]
  (let [message-id (str (or (u/jget payload "messageId") ""))
        channel-id (str (or (u/jget payload "channelId") ""))]
    (when (and (u/present-string? message-id) (u/present-string? channel-id))
      (let [emoji-name (str (or (u/jget payload "emoji" "name") ""))
            emoji-id (str (or (u/jget payload "emoji" "id") ""))
            emoji (if (u/present-string? emoji-name) emoji-name emoji-id)
            user-id (str (or (u/jget payload "userId") ""))]
        (when (events/label-emoji? emoji-name emoji)
          (handle-label-reaction-remove! state message-id channel-id user-id))))))

;; ─────────────────────────────────────────────────────────────
;; Settings UI
;; ─────────────────────────────────────────────────────────────

(defn- append-text! [document root tag text]
  (let [node (.createElement document tag)]
    (set! (.-textContent node) text)
    (.appendChild root node)
    node))

(defn- field! [document label-text plugin setting-key value placeholder password?]
  (let [wrap (.createElement document "label")
        label (.createElement document "span")
        input (.createElement document "input")]
    (set! (.-cssText (.-style wrap)) "display:flex;flex-direction:column;gap:6px;font-weight:600;")
    (set! (.-textContent label) label-text)
    (set! (.-type input) (if password? "password" "text"))
    (set! (.-value input) value)
    (set! (.-placeholder input) placeholder)
    (set! (.-cssText (.-style input)) "padding:8px 10px;border-radius:6px;border:1px solid var(--background-modifier-border);background:var(--background-secondary);color:var(--text-normal);")
    (.addEventListener input "change" #(save-setting! plugin setting-key (.-value input)))
    (.append wrap label input)
    wrap))

(defn- button! [document label on-click]
  (let [btn (.createElement document "button")]
    (set! (.-textContent btn) label)
    (set! (.-cssText (.-style btn)) "padding:10px 16px;border-radius:6px;border:none;background:var(--button-background);color:var(--button-text);cursor:pointer;")
    (.addEventListener btn "click" on-click)
    btn))

(defn- connection-status [state]
  (let [socket-state (state-get state "socket-state")
        connected (and socket-state (u/jget socket-state "connected"))]
    (if connected "connected to bot" "disconnected from bot")))

(defn settings-panel
  "Builds and returns the BetterDiscord settings panel DOM element."
  [plugin-meta state]
  (when-let [document (global-get "document")]
    (let [root (.createElement document "div")
          title (.createElement document "h2")
          saved-url (str (setting "BitchTracker" "botUrl" ""))]
      (set! (.-cssText (.-style root)) "padding:16px;display:flex;flex-direction:column;gap:12px;color:var(--text-normal);")
      (set! (.-textContent title) (meta-field plugin-meta "name" c/plugin-name))
      (.appendChild root title)
      (.append root
               (field! document "Bot socket.io URL" "BitchTracker" "botUrl" saved-url "http://127.0.0.1:7878" false)
               (append-text! document root "div" (str "Status: " (connection-status state)))
               (append-text! document root "div" (str "Allowlisted guild IDs: " (str/join ", " (bot-config-set state "guildIds"))))
               (append-text! document root "div" (str "Tracker channel: " c/tracker-channel-id))
               (append-text! document root "div" (str "Watch channel: " c/watch-channel-id))
               (append-text! document root "div" (str "Tracked users: " (.-size (state-get state "bitchCounts")) ", labeled messages: " (.-size (state-get state "labeledMessages"))))
               (button! document "Backfill Now" #(do (toast! "Backfill requested" "info")
                                                     (when-let [socket-state (state-get state "socket-state")]
                                                       (socket/send-backfill! socket-state #js {:ts (u/now-iso)}))))
               (button! document "Test Tracker Send" #(do (toast! "Tracker send requested" "info")
                                                          (when-let [socket-state (state-get state "socket-state")]
                                                            (socket/send-event! socket-state
                                                                                #js {:kind "discord.test"
                                                                                     :text "[bitch-tracker-test] tracker send test"
                                                                                     :ts (u/now-iso)})))))
      root)))

;; ─────────────────────────────────────────────────────────────
;; Lifecycle
;; ─────────────────────────────────────────────────────────────

(defn ^:async start-plugin!
  "Starts the plugin: loads Discord modules, restores label state, connects to the bot, and subscribes to events."
  [plugin-meta state]
  (await (sleep-ms 0))
  (load-modules! state)
  (load-label-state! state)
  (connect-to-bot! state plugin-meta)
  (let [dispatcher (state-get state "dispatcher")]
    (if-not (and dispatcher (u/jget dispatcher "subscribe"))
      (log! "error" plugin-meta "Could not find Discord Dispatcher.subscribe; lifecycle started in degraded mode")
      (do
        (state-set! state "boundMessageCreate" #(on-message-create! state %))
        (state-set! state "boundReactionAdd" #(on-reaction-add! state %))
        (state-set! state "boundReactionRemove" #(on-reaction-remove! state %))
        (u/jcall dispatcher "subscribe" "MESSAGE_CREATE" (state-get state "boundMessageCreate"))
        (u/jcall dispatcher "subscribe" "MESSAGE_REACTION_ADD" (state-get state "boundReactionAdd"))
        (u/jcall dispatcher "subscribe" "MESSAGE_REACTION_REMOVE" (state-get state "boundReactionRemove")))))
   (state-set! state "started" true)
   (state-set! state "startedAt" (u/now-iso))
   (state-set! state "stoppedAt" nil)
   (log! "info" plugin-meta "started" (str "v" (meta-field plugin-meta "version" c/plugin-version)) "no-guild-filter")
   (toast! (str (meta-field plugin-meta "name" c/plugin-name) " started") "success")
  state)

(defn stop-plugin!
  "Stops the plugin: unsubscribes from Discord events, disconnects from the bot, and persists label state."
  [plugin-meta state]
  (let [dispatcher (state-get state "dispatcher")]
    (try
      (when dispatcher
        (u/jcall dispatcher "unsubscribe" "MESSAGE_CREATE" (state-get state "boundMessageCreate"))
        (u/jcall dispatcher "unsubscribe" "MESSAGE_REACTION_ADD" (state-get state "boundReactionAdd"))
        (u/jcall dispatcher "unsubscribe" "MESSAGE_REACTION_REMOVE" (state-get state "boundReactionRemove")))
      (catch :default err (log! "warn" plugin-meta "unsubscribe failed" err))))
  (disconnect-from-bot! state)
  (persist-label-state! state)
  (state-set! state "started" false)
  (state-set! state "stoppedAt" (u/now-iso))
  (state-set! state "runningBackfill" false)
  (log! "info" plugin-meta "stopped")
  (toast! (str (meta-field plugin-meta "name" c/plugin-name) " stopped") "info")
  state)

(defn ^:async run-backfill!
  "Requests a message backfill from the bot if one is not already running."
  [state]
  (if (state-get state "runningBackfill")
    (toast! "Backfill already running" "warning")
    (do
      (state-set! state "runningBackfill" true)
      (toast! "Backfill requested from bot" "info")
      (when-let [socket-state (state-get state "socket-state")]
        (socket/send-backfill! socket-state #js {:ts (u/now-iso)}))
      (js/setTimeout #(state-set! state "runningBackfill" false) 2000)
      true)))

(defn ^:async flush!
  "Requests a fresh bot config from the connected socket."
  [state]
  (when-let [socket-state (state-get state "socket-state")]
    (socket/request-config! socket-state))
  true)

(defn plugin-factory
  "Returns a BetterDiscord plugin factory object for the given plugin metadata."
  [plugin-meta]
  (let [state (make-state)]
    #js {:start (fn [] (start-plugin! plugin-meta state))
         :stop (fn [] (stop-plugin! plugin-meta state))
         :getSettingsPanel (fn [] (settings-panel plugin-meta state))
         :runBackfill (fn [] (run-backfill! state))
         :flush (fn [] (flush! state))
         :state state}))

(defn main!
  "Entry point: exports the plugin factory to BetterDiscord's module loader."
  []
  ;; BetterDiscord's current plugin loader accepts this factory shape:
  ;; module.exports = meta => ({start, stop, ...})
  (set! (.-exports js/module) plugin-factory))
