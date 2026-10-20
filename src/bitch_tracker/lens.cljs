(ns bitch-tracker.lens
  "KnoxxLens BetterDiscord plugin: a drawer for asking a Knoxx agent about the
  open channel, with explicit context chips (channel tail + pinned messages).
  Talks to Knoxx only through the bitch-tracker bot broker."
  (:require [bitch-tracker.domain.lens-context :as ctx]
            [bitch-tracker.domain.lens-session :as session]
            [bitch-tracker.lens.discord :as discord]
            [bitch-tracker.lens.panel :as panel]
            [bitch-tracker.plugin.bd :as bd]
            [bitch-tracker.plugin.socket-client :as socket]
            [bitch-tracker.shape.protocol :as proto]
            [bitch-tracker.shared.constants :as c]
            [clojure.string :as str]))

(def plugin-name
  "Display and BdApi.Data namespace for the plugin."
  "KnoxxLens")

(def ^:private offline-config
  {:ready false :problems ["Waiting for the bitch-tracker bot (KnoxxLens broker)…"] :model nil})

(defn- make-app []
  {:started? false
   :stores nil
   :refs nil
   :socket-state nil
   :connected? false
   :lens-config offline-config
   :open? false
   :channel-messages {}
   :selections {}
   :conversations {}
   :requests {}
   :cleanup []
   :render-scheduled? false})

;; ─────────────────────────────────────────────────────────────
;; Settings + persistence
;; ─────────────────────────────────────────────────────────────

(defn- bot-url []
  (let [saved (str (bd/setting plugin-name "botUrl" ""))]
    (if (str/blank? saved) c/default-bot-url saved)))

(defn- budget-chars []
  (let [saved (js/parseInt (str (bd/setting plugin-name "budgetChars" "")) 10)]
    (if (and (number? saved) (not (js/isNaN saved)) (pos? saved)) saved ctx/default-budget-chars)))

(defn- persist-conversations! [app]
  (bd/data-save! plugin-name "conversations"
                 (clj->js (update-vals (:conversations @app) #(select-keys % [:conversation-id :session-id])))))

(defn- load-conversations []
  (->> (js->clj (or (bd/data-load plugin-name "conversations") #js {}) :keywordize-keys true)
       (keep (fn [[channel-id {:keys [conversation-id session-id]}]]
               (when (seq conversation-id)
                 [(name channel-id) (assoc (session/new-conversation conversation-id) :session-id session-id)])))
       (into {})))

;; ─────────────────────────────────────────────────────────────
;; Derived state
;; ─────────────────────────────────────────────────────────────

(defn- current-channel [{:keys [stores]}]
  (when-let [channel-id (and stores (discord/selected-channel-id stores))]
    (discord/channel-info stores channel-id)))

(defn- selection-for [app channel]
  (assoc (or (get-in app [:selections (:id channel)]) (ctx/empty-selection channel)) :channel channel))

(defn- conversation-for [app channel-id]
  (or (get-in app [:conversations channel-id])
      (session/new-conversation (str (random-uuid)))))

(defn- resolve-current [app channel]
  (let [cached (get-in app [:channel-messages (:id channel)])
        live (discord/loaded-messages (:stores app) (:id channel))
        messages (ctx/merge-messages cached live)]
    (ctx/resolve-context (selection-for app channel)
                         messages
                         (budget-chars))))

(defn- truncate [s n]
  (let [s (str/replace (str s) #"\s+" " ")]
    (if (> (count s) n) (str (subs s 0 n) "…") s)))

(declare render-soon!)

(defn- view [app* app]
  (let [channel (current-channel app)
        selection (when channel (selection-for app channel))
        resolved (when channel (resolve-current app channel))
        tail-item (some #(when (= :channel-tail (:kind %)) %) (:items resolved))
        conversation (when channel (get-in app [:conversations (:id channel)]))
        update-selection! (fn [f & args]
                            (swap! app* update-in [:selections (:id channel)]
                                   #(apply f (or % (ctx/empty-selection channel)) args))
                            (render-soon! app*))]
    {:open? (:open? app)
     :channel channel
     :connected? (:connected? app)
     :lens-config (:lens-config app)
     :running? (session/running? conversation)
     :turns (:turns conversation)
     :over-budget? (or (:over-budget? resolved) (pos? (:dropped resolved 0)))
     :budget-text (if resolved
                    (str "≈ " (ctx/estimate-tokens (:chars resolved)) " tokens of context"
                         " · budget ≈ " (ctx/estimate-tokens (budget-chars))
                         (when (pos? (:dropped resolved 0)) (str " · " (:dropped resolved) " older messages dropped"))
                         (when (:over-budget? resolved) " · pinned messages exceed budget"))
                    "Open a channel to select context.")
     :chips (when selection
              (into [{:label (str (if (:tail-enabled? selection) "✓ " "")
                                  "#" (:name channel) " · last "
                                  (if tail-item (count (:event-ids tail-item)) 0) "/" (:tail-limit selection))
                      :off? (not (:tail-enabled? selection))
                      :on-click #(update-selection! ctx/toggle-tail)}]
                    (map (fn [m]
                           {:label (str "📌 " (truncate (:author m) 16) ": " (truncate (:text m) 32))
                            :removable? true
                            :on-click #(update-selection! ctx/unpin-message (:event-id m))}))
                    (:pinned selection)))}))

(defn- render! [app*]
  (let [app @app*]
    (when-let [refs (:refs app)]
      (panel/render! refs (view app* app)))))

(defn- render-soon!
  "Coalesces renders into one animation frame (streaming tokens arrive fast)."
  [app*]
  (when-not (:render-scheduled? @app*)
    (swap! app* assoc :render-scheduled? true)
    (let [raf (or (bd/global-get "requestAnimationFrame") #(js/setTimeout % 16))]
      (raf (fn [_]
             (swap! app* assoc :render-scheduled? false)
             (render! app*))))))

;; ─────────────────────────────────────────────────────────────
;; Actions
;; ─────────────────────────────────────────────────────────────

(defn- set-open! [app* open?]
  (swap! app* assoc :open? open?)
  (render! app*)
  (when open? (some-> (get-in @app* [:refs :input]) .focus)))

(defn- send! [app* raw-text]
  (let [app @app*
        channel (current-channel app)
        {:keys [text tail-limit]} (ctx/parse-directives raw-text)]
    (when (and channel tail-limit)
      (swap! app* update-in [:selections (:id channel)] #(ctx/set-tail-limit (or % (ctx/empty-selection channel)) tail-limit)))
    (cond
      (nil? channel) (bd/toast! "Open a channel first" "warning")
      (str/blank? text) (render! app*)
      (not (:connected? app)) (bd/toast! "KnoxxLens: bot is offline" "error")
      (session/running? (conversation-for app (:id channel))) (bd/toast! "Knoxx is still answering" "warning")
      :else
      (let [app @app*
            {:keys [items]} (resolve-current app channel)
            conversation (conversation-for app (:id channel))
            request-id (str (random-uuid))]
        (swap! app* (fn [a]
                      (-> a
                          (assoc-in [:conversations (:id channel)]
                                    (session/begin-turn conversation request-id text (mapv :label items)))
                          (assoc-in [:requests request-id] (:id channel)))))
        (socket/emit! (:socket-state app) proto/lens-ask-to-bot
                      (clj->js {:requestId request-id
                                :conversationId (:conversation-id conversation)
                                :sessionId (:session-id conversation)
                                :message text
                                :channel channel
                                :items (mapv #(select-keys % [:kind :label :text :event-ids]) items)}))
        (panel/clear-input! (:refs app))
        (render! app*)))))

(defn- stop-turn! [app*]
  (when-let [channel (current-channel @app*)]
    (when-let [conversation (get-in @app* [:conversations (:id channel)])]
      (socket/emit! (:socket-state @app*) proto/lens-abort-to-bot
                    #js {:conversationId (:conversation-id conversation)}))))

(defn- new-conversation! [app*]
  (when-let [channel (current-channel @app*)]
    (if (session/running? (get-in @app* [:conversations (:id channel)]))
      (bd/toast! "Stop the running answer first" "warning")
      (do (swap! app* assoc-in [:conversations (:id channel)] (session/new-conversation (str (random-uuid))))
          (persist-conversations! app*)
          (render! app*)))))

(defn- pin-from-menu! [app* message channel-id]
  (let [{:keys [stores]} @app*
        channel (discord/channel-info stores channel-id)
        context-message (discord/message->context stores message channel-id)]
    (swap! app* update-in [:selections channel-id] #(ctx/pin-message (or % (ctx/empty-selection channel)) context-message))
    (bd/toast! "Added to Knoxx context" "success")
    (set-open! app* true)))

(defn- on-reply [app* kind]
  (fn [data]
    (let [reply (js->clj data :keywordize-keys true)
          request-id (:requestId reply)]
      (when-let [channel-id (get-in @app* [:requests request-id])]
        (swap! app* update-in [:conversations channel-id] session/apply-reply kind request-id reply)
        (when (#{:done :error} kind) (swap! app* update :requests dissoc request-id))
        (when (= :accepted kind) (persist-conversations! app*))
        (when (= :error kind) (bd/toast! (str "Knoxx: " (:error reply)) "error"))
        (render-soon! app*)))))

;; ─────────────────────────────────────────────────────────────
;; Bot connection
;; ─────────────────────────────────────────────────────────────

(defn- connect! [app*]
  (let [socket-state (socket/make-state)
        user (discord/current-user (:stores @app*))]
    (swap! app* assoc :socket-state socket-state)
    (socket/connect! socket-state (bot-url)
                     #js {:userId (:id user)
                          :username (:username user)
                          :plugin plugin-name
                          :hostname (some-> (bd/global-get "location") .-hostname)}
                     {:on-status (fn [data]
                                   (case (some-> data .-status)
                                     "connected" (do (swap! app* assoc :connected? true)
                                                     (socket/emit! socket-state proto/lens-config-request-to-bot #js {}))
                                     "disconnected" (swap! app* assoc :connected? false :lens-config offline-config)
                                     nil)
                                   (render-soon! app*))})
    (socket/on! socket-state proto/lens-config-response-to-plugin
                (fn [data]
                  (swap! app* assoc :lens-config (js->clj data :keywordize-keys true))
                  (render-soon! app*)))
    (doseq [[event-name kind] [[proto/lens-accepted-to-plugin :accepted]
                               [proto/lens-token-to-plugin :token]
                               [proto/lens-event-to-plugin :event]
                               [proto/lens-done-to-plugin :done]
                               [proto/lens-error-to-plugin :error]]]
      (socket/on! socket-state event-name (on-reply app* kind)))))

;; ─────────────────────────────────────────────────────────────
;; Lifecycle
;; ─────────────────────────────────────────────────────────────

(defn- keybind-handler [app*]
  (fn [^js e]
    (when (and (.-ctrlKey e) (.-shiftKey e) (= "k" (str/lower-case (str (.-key e)))))
      (.preventDefault e)
      (set-open! app* (not (:open? @app*))))))

(defn- harvest-channel! [app* channel-id incoming]
  (when (and (seq (str channel-id)) (seq incoming))
    (swap! app* update-in [:channel-messages (str channel-id)] ctx/merge-messages incoming)))

(defn- harvest-open-channel! [app*]
  (let [{:keys [stores]} @app*]
    (when-let [channel-id (and stores (discord/selected-channel-id stores))]
      (harvest-channel! app* channel-id (discord/loaded-messages stores channel-id)))))

(defn- on-discord-action! [app* ^js action]
  (let [{:keys [stores]} @app*]
    (when stores
      (when-let [channel-id (discord/action-channel-id action)]
        (harvest-channel! app* channel-id (discord/action-messages stores action))
        (harvest-channel! app* channel-id (discord/loaded-messages stores channel-id)))
      (harvest-open-channel! app*)))
  (when (:open? @app*)
    (render-soon! app*)))

(defn ^:async start!
  "Loads stores, restores conversation ids, mounts the drawer, patches the message menu, and connects to the bot."
  [plugin-meta app*]
  (await (bd/sleep-ms 0))
  (let [stores (discord/load-stores)
        document (bd/global-get "document")
        refs (when document
               (panel/mount! {:on-toggle #(set-open! app* (not (:open? @app*)))
                              :on-close #(set-open! app* false)
                              :on-new #(new-conversation! app*)
                              :on-send #(send! app* %)
                              :on-stop #(stop-turn! app*)}))
        keydown (keybind-handler app*)
        on-action #(on-discord-action! app* %)]
    (swap! app* assoc :stores stores :refs refs :conversations (load-conversations))
    (harvest-open-channel! app*)
    (connect! app*)
    (when document (.addEventListener document "keydown" keydown))
    (swap! app* assoc :cleanup
           (->> [(discord/patch-message-menu! plugin-name #(pin-from-menu! app* %1 %2))
                 (discord/subscribe! stores "CHANNEL_SELECT" on-action)
                 (discord/subscribe! stores "LOAD_MESSAGES_SUCCESS" on-action)
                 (discord/subscribe! stores "MESSAGE_CREATE" on-action)
                 (discord/subscribe! stores "MESSAGE_UPDATE" on-action)
                 (when document #(.removeEventListener document "keydown" keydown))]
                (remove nil?)
                vec))
    (swap! app* assoc :started? true)
    (render! app*)
    (bd/log! "info" plugin-name "started" (str "v" (bd/meta-field plugin-meta "version" "0.0.1")))
    app*))

(defn stop!
  "Unpatches, unsubscribes, unmounts, and disconnects."
  [app*]
  (doseq [cleanup (:cleanup @app*)]
    (try (cleanup) (catch :default err (bd/log! "warn" plugin-name "cleanup failed" err))))
  (when-let [refs (:refs @app*)] (panel/unmount! refs))
  (when-let [socket-state (:socket-state @app*)] (socket/disconnect! socket-state))
  (persist-conversations! app*)
  (swap! app* assoc :started? false :cleanup [] :refs nil :socket-state nil :connected? false)
  (bd/log! "info" plugin-name "stopped")
  app*)

(defn settings-panel
  "Builds the BetterDiscord settings panel."
  [app*]
  (when-let [document (bd/global-get "document")]
    (let [root (.createElement document "div")
          field (fn [label setting-key value placeholder]
                  (let [wrap (.createElement document "label")
                        span (.createElement document "span")
                        input (.createElement document "input")]
                    (set! (.-cssText (.-style wrap)) "display:flex;flex-direction:column;gap:6px;font-weight:600;")
                    (set! (.-textContent span) label)
                    (set! (.-value input) (str value))
                    (set! (.-placeholder input) placeholder)
                    (set! (.-cssText (.-style input)) "padding:8px 10px;border-radius:6px;border:1px solid var(--background-modifier-border);background:var(--background-secondary);color:var(--text-normal);")
                    (.addEventListener input "change" #(bd/data-save! plugin-name setting-key (.-value input)))
                    (.append wrap span input)
                    wrap))
          {:keys [connected? lens-config]} @app*
          status (.createElement document "div")]
      (set! (.-cssText (.-style root)) "padding:16px;display:flex;flex-direction:column;gap:12px;color:var(--text-normal);")
      (set! (.-textContent status)
            (str "Bot: " (if connected? "connected" "offline")
                 " · Knoxx: " (if (:ready lens-config) (str "ready (" (:model lens-config) ")") "blocked")
                 (when (seq (:problems lens-config)) (str " — " (str/join "; " (:problems lens-config))))))
      (.append root
               (field "Bot socket.io URL (reconnects on plugin restart)" "botUrl" (bd/setting plugin-name "botUrl" "") c/default-bot-url)
               (field "Context budget (characters, ~4 per token)" "budgetChars" (bd/setting plugin-name "budgetChars" "") (str ctx/default-budget-chars))
               status)
      root)))

(defn plugin-factory
  "Returns a BetterDiscord plugin object for the given metadata."
  [plugin-meta]
  (let [app* (atom (make-app))]
    #js {:start (fn [] (start! plugin-meta app*))
         :stop (fn [] (stop! app*))
         :getSettingsPanel (fn [] (settings-panel app*))
         :state app*}))

(defn main!
  "Entry point: exports the plugin factory to BetterDiscord's module loader."
  []
  (set! (.-exports js/module) plugin-factory))
