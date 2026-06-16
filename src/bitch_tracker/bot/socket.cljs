(ns bitch-tracker.bot.socket
  (:require [bitch-tracker.bot.config :as cfg]
            [bitch-tracker.bot.dedup :as dedup]
            [bitch-tracker.bot.discord :as discord]
            [bitch-tracker.bot.openplanner :as op]
            [bitch-tracker.shape.protocol :as proto]
            [bitch-tracker.shared.util :as u]
            [clojure.string :as str]))

(def ^js socket-io
  "The socket.io server constructor."
  (js/require "socket.io"))

(defn make-state
  "Returns a fresh bot socket server state object."
  []
  #js {"label_reactors" (js/Map.)
       "io" nil
       "discord_client" nil
       "op_state" nil
       "dedup_state" nil
       "config" nil
       "watchlist" nil})

;; ─────────────────────────────────────────────────────────────
;; Label tracking: each (message-author, message-id, reactor-id)
;; tuple counts once. Multiple moderators can label the same message
;; and each contributes to the author's total.
;; ─────────────────────────────────────────────────────────────

(defn- label-key [user-id message-id]
  (str user-id "\u0000" message-id))

(defn- add-label-reactor! [^js state user-id message-id reactor-id]
  (let [k (label-key user-id message-id)
        reactors (or (.get (.-label_reactors state) k) (js/Set.))]
    (when (= 0 (.-size reactors))
      (.set (.-label_reactors state) k reactors))
    (when-not (.has reactors reactor-id)
      (.add reactors reactor-id)
      true)))

(defn- remove-label-reactor! [^js state user-id message-id reactor-id]
  (let [k (label-key user-id message-id)
        reactors (.get (.-label_reactors state) k)]
    (when reactors
      (.delete reactors reactor-id)
      (= 0 (.-size reactors)))))

(defn label-count
  "Returns the number of distinct label reactions for user-id."
  [^js state user-id]
  (let [reactors-map (.-label_reactors state)
        total (atom 0)]
    (doseq [entry (array-seq (js/Array.from (.entries reactors-map)))]
      (let [entry-key (aget entry 0)
            reactors (aget entry 1)]
        (when (str/starts-with? entry-key (str user-id "\u0000"))
          (swap! total + (.-size reactors)))))
    @total))

(defn- emit-to-all [^js state event-name data]
  (when-let [^js io (.-io state)]
    (.emit io event-name data)))

(defn- broadcast-status [^js state status]
  (emit-to-all state proto/status-to-plugin
               #js {:status status
                    :ts (u/now-iso)}))

(defn- moderation-hits [^js state text]
  (let [entries (cfg/watch-entries (.-watchlist state))
        content (str text)]
    (->> entries
         (map (fn [{:keys [label pattern]}]
                (when-let [match (.exec pattern content)]
                  {:label label
                   :index (.-index match)
                   :matched (str (aget match 0))
                   :length (count (str (aget match 0)))})))
         (remove nil?)
         (reduce (fn [acc hit]
                   (if (some #(= (:label hit) (:label %)) acc)
                     acc
                     (conj acc hit)))
                 [])
         vec)))

(defn- event-id [event]
  (str (u/jget event "id")))

(defn- dedup-ttl-ms [_config]
  (* 1000 60 60 24)) ;; 24 hours

(defn- dedup-max-size [config]
  (or (:dedup-max-size config) 50000))

(defn handle-incoming-event!
  "Routes an incoming plugin event to OpenPlanner and tracker channels."
  [^js state event]
  (let [^js op-state (.-op_state state)
        config (.-config state)
        ^js discord-client (.-discord_client state)
        ^js dedup-state (.-dedup_state state)]
    (when (and event dedup-state)
      (when (dedup/add! dedup-state (event-id event) (dedup-ttl-ms config) (dedup-max-size config))
        (when (and op-state config)
          (op/queue-event! op-state event (:max-persisted-events config)))
        ;; Route moderation-watch and known-watch-user messages to tracker channel.
        (when (and discord-client config)
          (let [text (str (u/jget event "text"))
                hits (moderation-hits state text)
                extra (u/jget event "extra")
                is-known? (boolean (u/jget extra "is_known_watch_user"))
                author-id (str (u/jget event "meta" "author_id"))]
            (when (or (seq hits) is-known?)
              (let [guild-id (str (u/jget extra "guild_id"))
                    channel-id (str (u/jget extra "channel_id"))
                    message-id (str (u/jget extra "message_id"))
                    guild-name (str (u/jget extra "guild_name"))
                    channel-name (str (u/jget extra "channel_name"))
                    message (clj->js {:id message-id
                                      :channel_id channel-id
                                      :guild_id guild-id
                                      :content text
                                      :author #js {:id author-id
                                                   :username (u/jget event "meta" "author")
                                                   :globalName (u/jget event "meta" "author_global_name")}
                                      :timestamp (u/jget event "ts")})
                    channel (clj->js {:id channel-id :name channel-name :guild_id guild-id})
                     reason (if is-known?
                              "known-watch-user"
                              (str "moderation-watch: " (str/join ", " (map :label hits))))]
                (when (:tracker-channel-id config)
                  (discord/send-message! discord-client
                                         (:tracker-channel-id config)
                                         (discord/format-tracker-message message channel guild-id guild-name reason hits)))))))))))

(defn handle-label-added!
  "Records a label reaction and forwards threshold alerts."
  [^js state data]
  (let [user-id (str (u/jget data "userId"))
        message-id (str (u/jget data "messageId"))
        reactor-id (str (or (u/jget data "reactorId") "unknown"))
        message (u/jget data "message")
        channel (u/jget data "channel")
        guild-id (str (u/jget data "guildId"))
        config (.-config state)
        ^js discord-client (.-discord_client state)
        previous-count (label-count state user-id)
        added? (add-label-reactor! state user-id message-id reactor-id)
        current-count (label-count state user-id)]
    (when added?
      ;; Forward to tracker channel
      (when (and discord-client (:tracker-channel-id config) message)
        (let [msg (discord/format-tracker-message message channel guild-id nil "poodle-labeled")]
          (discord/send-message! discord-client (:tracker-channel-id config) msg)))
      ;; Check threshold for watch alert
      (when (and (>= current-count (:label-threshold config))
                 (< previous-count (:label-threshold config)))
        (when (and discord-client (:watch-channel-id config) message)
          (let [author (u/jget message "author")
                author-name (u/sanitize-mentions (or (u/jget author "username") (u/jget author "globalName") "Unknown"))
                msg (discord/format-watch-message user-id author-name current-count message)]
            (discord/send-message! discord-client (:watch-channel-id config) msg)))
        (emit-to-all state proto/watch-alert-to-plugin
                     #js {:userId user-id
                          :messageId message-id
                          :count current-count
                          :ts (u/now-iso)})))))

(defn handle-label-removed!
  "Removes a label reaction for a message."
  [^js state data]
  (let [user-id (str (u/jget data "userId"))
        message-id (str (u/jget data "messageId"))
        reactor-id (str (or (u/jget data "reactorId") "unknown"))]
    (remove-label-reactor! state user-id message-id reactor-id)))

(defn- handle-backfill! [^js state _data]
  ;; Backfill is triggered by a plugin. In a multi-user deployment each plugin
  ;; may request it; the bot just acknowledges. Real backfill is currently a
  ;; no-op stub because the plugin no longer ships message history.
  (js/console.log "[socket] Backfill requested")
  (broadcast-status state "backfill-started"))

(defn- handle-config-request! [^js state ^js socket]
  (let [config (.-config state)]
    (.emit socket proto/config-response-to-plugin
           #js {:trackerChannelId (:tracker-channel-id config)
                :watchChannelId (:watch-channel-id config)
                :labelThreshold (:label-threshold config)
                :botUserId (:bot-user-id config)
                :guildIds (clj->js (:guild-ids config))
                :knownLabelUserIds (clj->js (:known-label-user-ids config))})))

(defn notify-bot-online!
  "Announces bot startup in the configured status channel."
  [^js state config]
  (let [^js discord-client (.-discord_client state)
        channel-id (:plugin-status-channel-id config)
        ^js os (js/require "os")]
    (when (and discord-client channel-id)
      (discord/send-message!
       discord-client
       channel-id
       (discord/format-status-message
        {:status :bot-online
         :hostname (.hostname os)})))))

(defn- notify-status! [^js state status socket]
  (let [config (.-config state)
        ^js discord-client (.-discord_client state)
        channel-id (:plugin-status-channel-id config)
        plugin-identity (when socket (.-plugin_identity ^js socket))
        user-id (when plugin-identity (str (u/jget plugin-identity "user_id")))
        username (when plugin-identity (str (u/jget plugin-identity "username")))
        hostname (when plugin-identity (str (u/jget plugin-identity "hostname")))
        socket-id (when socket (.-id socket))
        slapper-role-id (:slapper-of-bitches-role-id config)]
    (when (and discord-client channel-id)
      (discord/send-message!
       discord-client
       channel-id
       (discord/format-status-message
        {:status status
         :user-id user-id
         :username username
         :hostname hostname
         :socket-id socket-id
         :slapper-role-id slapper-role-id})))))

(defn on-connection!
  "Wires up event handlers for a new plugin socket connection."
  [^js state ^js socket]
  (js/console.log "[socket] Plugin connected:" (.-id socket))

  (.on socket proto/event-to-bot
       (fn [data] (handle-incoming-event! state data)))

  (.on socket proto/label-added-to-bot
       (fn [data] (handle-label-added! state data)))

  (.on socket proto/label-removed-to-bot
       (fn [data] (handle-label-removed! state data)))

  (.on socket proto/backfill-to-bot
       (fn [data] (handle-backfill! state data)))

  (.on socket proto/config-request-to-bot
       (fn [_data] (handle-config-request! state socket)))

  (.on socket proto/plugin-identify-to-bot
       (fn [data]
         (set! (.-plugin_identity socket) (clj->js
                                           {:user_id (str (u/jget data "userId"))
                                            :username (str (u/jget data "username"))
                                            :hostname (str (u/jget data "hostname"))}))
         (notify-status! state :plugin-connected socket)))

  (.on socket "disconnect"
       (fn []
         (notify-status! state :plugin-disconnected socket)
         (js/console.log "[socket] Plugin disconnected:" (.-id socket))))

  (.emit socket proto/status-to-plugin
         #js {:status "connected"
              :botUserId (:bot-user-id (.-config state))
              :ts (u/now-iso)}))

(defn start!
  "Starts the socket.io server on the configured port."
  [^js state config]
  (let [^js io (new (.-Server socket-io) #js {:cors #js {:origin "*"}})]
    (set! (.-io state) io)
    (set! (.-config state) config)
    (set! (.-watchlist state) (cfg/load-watchlist))
    (.on io "connection" (fn [^js socket] (on-connection! state socket)))
    (.listen io (:socket-port config))
    (js/console.log "[socket] Socket.io server listening on port" (:socket-port config))
    state))

(defn stop!
  "Stops the socket.io server and clears the state socket."
  [^js state]
  (when-let [^js io (.-io state)]
    (.close io)
    (set! (.-io state) nil))
  state)
