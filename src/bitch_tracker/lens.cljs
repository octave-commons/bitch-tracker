(ns bitch-tracker.lens
  "KnoxxLens BetterDiscord plugin: a drawer for asking a Knoxx agent about the
  open channel, with explicit context chips (channel tail + pinned messages),
  Markdown rendering, collapsible tool receipts, and past-session navigation.
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
   :sessions-open? false
   :channel-only-sessions? false
   :channel-messages {}
   :selections {}
   :conversations {}
   :session-history {}
   :remote-sessions []
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

(defn- normalize-loaded-receipt [r]
  (-> r
      (update :status #(keyword (or % :done)))
      (update :updates #(vec (or % [])))))

(defn- normalize-loaded-turn [t]
  (cond-> (assoc t :role (keyword (or (:role t) :assistant)))
    (:status t) (update :status keyword)
    (:events t) (update :events vec)
    (:tool-receipts t) (update :tool-receipts #(mapv normalize-loaded-receipt %))))

(defn- normalize-loaded-conversation [channel-id {:keys [conversation-id session-id channel-name title updated-at turns]}]
  (when (seq (str conversation-id))
    (cond-> (assoc (session/new-conversation (str conversation-id))
                   :session-id session-id
                   :turns (mapv normalize-loaded-turn (or turns [])))
      channel-id (assoc :channel-id (str channel-id))
      channel-name (assoc :channel-name (str channel-name))
      title (assoc :title (str title))
      updated-at (assoc :updated-at (str updated-at)))))

(defn- serialize-conversation [conv]
  (select-keys conv [:conversation-id :session-id :channel-id :channel-name :title :updated-at :turns]))

(defn- archive-conversation [history conv]
  (if (and conv (seq (:conversation-id conv)) (seq (:turns conv)))
    (let [entry (-> conv
                    (assoc :title (session/conversation-title conv)
                           :updated-at (or (:updated-at conv) (.toISOString (js/Date.))))
                    serialize-conversation)
          merged (assoc (or history {}) (:conversation-id conv) entry)]
      (->> (vals merged)
           (sort-by #(or (:updated-at %) "") #(compare %2 %1))
           (take 50)
           (map (fn [c] [(:conversation-id c) c]))
           (into {})))
    (or history {})))

(defn- persist-conversations! [app*]
  (let [app @app*
        history (reduce (fn [acc [ch-id conv]]
                          (archive-conversation acc (assoc conv :channel-id ch-id)))
                        (:session-history app)
                        (:conversations app))]
    (swap! app* assoc :session-history history)
    (bd/data-save! plugin-name "conversations"
                   (clj->js (update-vals (:conversations app) serialize-conversation)))
    (bd/data-save! plugin-name "sessionHistory"
                   (clj->js history))))

(defn- load-conversations []
  (->> (js->clj (or (bd/data-load plugin-name "conversations") #js {}) :keywordize-keys true)
       (keep (fn [[channel-id raw]]
               (when-let [conv (normalize-loaded-conversation (name channel-id) raw)]
                 [(name channel-id) conv])))
       (into {})))

(defn- load-session-history []
  (->> (js->clj (or (bd/data-load plugin-name "sessionHistory") #js {}) :keywordize-keys true)
       (keep (fn [[cid raw]]
               (when-let [conv (normalize-loaded-conversation (:channel-id raw) (assoc raw :conversation-id (name cid)))]
                 [(name cid) conv])))
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
(declare select-session!)

(defn- local-session-list [app]
  (let [from-channels (keep (fn [[ch-id conv]]
                              (when (seq (:turns conv))
                                (assoc conv :channel-id (or (:channel-id conv) ch-id))))
                            (:conversations app))
        from-history (vals (:session-history app))]
    (concat from-history from-channels)))

(defn- view [app* app]
  (let [channel (current-channel app)
        selection (when channel (selection-for app channel))
        resolved (when channel (resolve-current app channel))
        tail-item (some #(when (= :channel-tail (:kind %)) %) (:items resolved))
        conversation (when channel (get-in app [:conversations (:id channel)]))
        all-sessions (session/merge-session-summaries (local-session-list app) (:remote-sessions app))
        filtered-sessions (if (and (:channel-only-sessions? app) channel)
                            (filterv #(or (= (str (:channel-id %)) (str (:id channel)))
                                          (= (str (:channel-name %)) (str (:name channel))))
                                     all-sessions)
                            all-sessions)
        update-selection! (fn [f & args]
                            (swap! app* update-in [:selections (:id channel)]
                                   #(apply f (or % (ctx/empty-selection channel)) args))
                            (render-soon! app*))]
    {:open? (:open? app)
     :sessions-open? (:sessions-open? app)
     :channel-only-sessions? (:channel-only-sessions? app)
     :sessions filtered-sessions
     :active-conversation-id (:conversation-id conversation)
     :on-select-session #(select-session! app* %)
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

(defn- request-remote-sessions! [app*]
  (let [{:keys [socket-state connected?]} @app*]
    (when (and socket-state connected?)
      (socket/emit! socket-state proto/lens-sessions-list-to-bot #js {:limit 40}))))

(defn- request-remote-session-detail! [app* conversation-id]
  (let [{:keys [socket-state connected?]} @app*]
    (when (and socket-state connected? (seq (str conversation-id)))
      (socket/emit! socket-state proto/lens-session-get-to-bot #js {:sessionId (str conversation-id)}))))

(defn- set-open! [app* open?]
  (swap! app* assoc :open? open?)
  (when open? (request-remote-sessions! app*))
  (render! app*)
  (when open? (some-> (get-in @app* [:refs :input]) .focus)))

(defn- toggle-history! [app*]
  (let [next-open? (not (:sessions-open? @app*))]
    (swap! app* assoc :sessions-open? next-open?)
    (when next-open? (request-remote-sessions! app*))
    (render! app*)))

(defn- toggle-history-filter! [app*]
  (swap! app* update :channel-only-sessions? not)
  (render! app*))

(defn- select-session! [app* {:keys [conversation-id session-id title channel-id channel-name]}]
  (let [app @app*
        channel (current-channel app)
        target-channel-id (or (:id channel) channel-id "global")
        current-conv (get-in app [:conversations target-channel-id])]
    (if (session/running? current-conv)
      (bd/toast! "Stop the running answer before switching sessions" "warning")
      (let [archived (archive-conversation (:session-history app)
                                           (cond-> current-conv
                                             channel (assoc :channel-id (:id channel)
                                                            :channel-name (:name channel))))
            cached (or (get archived conversation-id)
                       (some (fn [[_ c]] (when (= conversation-id (:conversation-id c)) c))
                             (:conversations app)))
            next-conv (cond-> (or cached (session/new-conversation conversation-id))
                        (seq (str session-id)) (assoc :session-id session-id)
                        (seq (str title)) (assoc :title title)
                        channel (assoc :channel-id (:id channel) :channel-name (:name channel))
                        (and (nil? channel) channel-name) (assoc :channel-name channel-name))]
        (swap! app* #(-> %
                         (assoc :session-history archived
                                :sessions-open? false)
                         (assoc-in [:conversations target-channel-id] next-conv)))
        (persist-conversations! app*)
        (request-remote-session-detail! app* conversation-id)
        (render! app*)))))

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
            conversation (-> (conversation-for app (:id channel))
                             (assoc :channel-id (:id channel)
                                    :channel-name (:name channel)
                                    :updated-at (.toISOString (js/Date.))))
            request-id (str (random-uuid))
            started-conv (session/begin-turn conversation request-id text (mapv :label items))]
        (swap! app* (fn [a]
                      (-> a
                          (assoc-in [:conversations (:id channel)] started-conv)
                          (assoc-in [:requests request-id] (:id channel)))))
        (persist-conversations! app*)
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
    (let [current-conv (get-in @app* [:conversations (:id channel)])]
      (if (session/running? current-conv)
        (bd/toast! "Stop the running answer first" "warning")
        (do
          (swap! app* (fn [a]
                        (-> a
                            (update :session-history archive-conversation
                                    (when current-conv
                                      (assoc current-conv
                                             :channel-id (:id channel)
                                             :channel-name (:name channel))))
                            (assoc-in [:conversations (:id channel)]
                                      (assoc (session/new-conversation (str (random-uuid)))
                                             :channel-id (:id channel)
                                             :channel-name (:name channel)))
                            (assoc :sessions-open? false))))
          (persist-conversations! app*)
          (render! app*))))))

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
        (swap! app* update-in [:conversations channel-id]
               (fn [conv]
                 (-> (session/apply-reply conv kind request-id reply)
                     (assoc :updated-at (.toISOString (js/Date.))))))
        (when (#{:done :error} kind)
          (swap! app* update :requests dissoc request-id)
          (persist-conversations! app*)
          (request-remote-sessions! app*))
        (when (= :accepted kind) (persist-conversations! app*))
        (when (= :error kind) (bd/toast! (str "Knoxx: " (:error reply)) "error"))
        (render-soon! app*)))))

(defn- on-sessions-list [app*]
  (fn [data]
    (let [res (js->clj data :keywordize-keys true)]
      (when (:ok res)
        (swap! app* assoc :remote-sessions (vec (or (:rows res) [])))
        (render-soon! app*)))))

(defn- on-session-detail [app*]
  (fn [data]
    (let [res (js->clj data :keywordize-keys true)
          cid (str (or (:sessionId res) ""))
          turns (when (:ok res) (session/rows->turns (:rows res)))]
      (when (and (seq cid) (seq turns))
        (swap! app*
               (fn [a]
                 (let [updated-convs (into {}
                                           (map (fn [[ch-id conv]]
                                                  [ch-id (if (and (= cid (:conversation-id conv))
                                                                  (not (session/running? conv))
                                                                  (>= (count turns) (count (:turns conv))))
                                                           (assoc conv :turns turns)
                                                           conv)]))
                                           (:conversations a))
                       hist-entry (or (get-in a [:session-history cid])
                                      (session/new-conversation cid))
                       updated-hist (assoc (:session-history a)
                                           cid
                                           (assoc hist-entry
                                                  :turns turns
                                                  :title (session/conversation-title (assoc hist-entry :turns turns))))]
                   (assoc a
                          :conversations updated-convs
                          :session-history updated-hist))))
        (persist-conversations! app*)
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
                                                     (socket/emit! socket-state proto/lens-config-request-to-bot #js {})
                                                     (request-remote-sessions! app*)
                                                     (when-let [ch (current-channel @app*)]
                                                       (let [conv (get-in @app* [:conversations (:id ch)])]
                                                         (when (and conv (empty? (:turns conv)))
                                                           (request-remote-session-detail! app* (:conversation-id conv))))))
                                     "disconnected" (swap! app* assoc :connected? false :lens-config offline-config)
                                     nil)
                                   (render-soon! app*))})
    (socket/on! socket-state proto/lens-config-response-to-plugin
                (fn [data]
                  (swap! app* assoc :lens-config (js->clj data :keywordize-keys true))
                  (render-soon! app*)))
    (socket/on! socket-state proto/lens-sessions-list-to-plugin (on-sessions-list app*))
    (socket/on! socket-state proto/lens-session-get-to-plugin (on-session-detail app*))
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
  "Loads stores, restores conversation ids and session history, mounts the drawer, patches the message menu, and connects to the bot."
  [plugin-meta app*]
  (await (bd/sleep-ms 0))
  (let [stores (discord/load-stores)
        document (bd/global-get "document")
        refs (when document
               (panel/mount! {:on-toggle #(set-open! app* (not (:open? @app*)))
                              :on-close #(set-open! app* false)
                              :on-new #(new-conversation! app*)
                              :on-history-toggle #(toggle-history! app*)
                              :on-history-filter #(toggle-history-filter! app*)
                              :on-history-refresh #(request-remote-sessions! app*)
                              :on-send #(send! app* %)
                              :on-stop #(stop-turn! app*)}))
        keydown (keybind-handler app*)
        on-action #(on-discord-action! app* %)]
    (swap! app* assoc
           :stores stores
           :refs refs
           :conversations (load-conversations)
           :session-history (load-session-history))
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
