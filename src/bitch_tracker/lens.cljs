(ns bitch-tracker.lens
  "KnoxxLens BetterDiscord plugin: a drawer for asking a Knoxx agent about the
  open channel, with smart tiered context (channel map + matched history + delta tail + pins),
  automatic & one-click Channel Pulse digests, an interactive Graph Memory explorer,
  Markdown rendering, collapsible tool receipts, and past-session navigation.
  Talks to Knoxx and OpenPlanner only through the bitch-tracker bot broker."
  (:require [bitch-tracker.domain.lens-context :as ctx]
            [bitch-tracker.domain.lens-session :as session]
            [bitch-tracker.domain.lens-turn :as turn]
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

(def ^:private empty-graph-state
  {:loading? false
   :query ""
   :nodes []
   :edges []
   :clusters []
   :stats nil
   :error nil
   :selected-node-id nil})

(def ^:private empty-conn-graph
  {:loading? false
   :query ""
   :nodes []
   :edges []
   :people-bridges []
   :timeframe nil
   :selected-node nil
   :error nil})

(def ^:private empty-extract-state
  {:loading? false
   :text ""
   :sanitized ""
   :entities []
   :merkle-root nil
   :dek-shredded nil
   :messages []
   :error nil})

(def ^:private initial-telemetry
  {:phase :idle
   :tool-name nil
   :ttft-ms nil
   :tokens-per-s nil
   :input-tokens nil
   :output-tokens nil})

(defn- make-app []
  {:started? false
   :stores nil
   :refs nil
   :socket-state nil
   :connected? false
   :lens-config offline-config
   :open? true
   :active-tab :chat
   :auto-pulse? true
   :pulse-timer nil
   :topics-timer nil
   :last-channel-id nil
   :graph-channel-id nil
   :sessions-open? false
   :channel-only-sessions? false
   :channel-messages {}
   :channel-topics {}
   :selections {}
   :conversations {}
   :digests {}
   :graph-state empty-graph-state
   :conn-graph empty-conn-graph
   :extract-state empty-extract-state
   :desktop-windows []
   :telemetry initial-telemetry
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

(defn- load-auto-pulse-setting []
  (let [saved (bd/setting plugin-name "autoPulse" "true")]
    (not= "false" (str saved))))

(defn- load-drawer-open []
  (not= false (bd/data-load plugin-name "drawerOpen")))

(defn- load-active-tab []
  (let [raw (str (or (bd/data-load plugin-name "activeTab") "chat"))]
    (if (#{"chat" "pulse" "graph" "connections" "extract" "windows"} raw)
      (keyword raw)
      :chat)))

(defn- normalize-loaded-receipt [r]
  (-> r
      (update :status #(keyword (or % :done)))
      (update :updates #(vec (or % [])))))

(defn- normalize-loaded-turn [t]
  (let [role (keyword (or (:role t) :assistant))
        deduped (when (= :assistant role) (session/dedupe-assistant-text (:text t)))
        artifacts (when (= :assistant role) (session/extract-turn-artifacts deduped))]
    (cond-> (assoc t :role role)
      (= :assistant role) (assoc :text (:text artifacts))
      (seq (:suggestions artifacts)) (assoc :suggestions (:suggestions artifacts))
      (seq (:drafts artifacts)) (assoc :drafts (:drafts artifacts))
      (seq (:refined-topics artifacts)) (assoc :refined-topics (:refined-topics artifacts))
      (:status t) (update :status keyword)
      (:events t) (update :events vec)
      (:tool-receipts t) (update :tool-receipts #(mapv normalize-loaded-receipt %)))))

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

(defn- channel-messages-for [app channel-id]
  (let [cached (get-in app [:channel-messages channel-id])
        live (when (:stores app) (discord/loaded-messages (:stores app) channel-id))]
    (ctx/merge-messages cached live)))

(defn- selection-for [app channel]
  (assoc (or (get-in app [:selections (:id channel)]) (ctx/empty-selection channel)) :channel channel))

(defn- conversation-for [app channel-id]
  (or (get-in app [:conversations channel-id])
      (session/new-conversation (str (random-uuid)))))

(defn- effective-channel-topics [app channel-id]
  (let [{:keys [agent-topics bot-topics]} (get-in app [:channel-topics channel-id])]
    (or (not-empty agent-topics)
        (not-empty bot-topics))))

(defn- resolve-current
  ([app channel]
   (resolve-current app channel {:include-map? true}))
  ([app channel opts]
   (let [messages (channel-messages-for app (:id channel))
         override-topics (or (:override-topics opts)
                             (effective-channel-topics app (:id channel)))]
     (ctx/resolve-context (selection-for app channel)
                          messages
                          (budget-chars)
                          (cond-> opts
                            (seq override-topics) (assoc :override-topics override-topics))))))

(defn- truncate [s n]
  (let [s (str/replace (str s) #"\s+" " ")]
    (if (> (count s) n) (str (subs s 0 n) "…") s)))

(declare render-soon!)
(declare select-session!)
(declare trigger-pulse!)
(declare promote-pulse!)
(declare trigger-graph-query!)
(declare explore-in-graph!)
(declare pin-graph-node!)
(declare use-suggestion!)
(declare stage-draft!)
(declare approve-draft!)
(declare trigger-connections-query!)
(declare on-conn-node-click!)
(declare trigger-desktop-focus!)
(declare trigger-desktop-windows-list!)
(declare pin-desktop-window!)
(declare trigger-govone-extract!)
(declare trigger-govone-channel-send!)
(declare trigger-govone-channel-read!)

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
        conversation (when channel (get-in app [:conversations (:id channel)]))
        resolved (when channel
                   (resolve-current app channel
                                    {:include-map? true
                                     :already-sent-ids (:sent-event-ids conversation)}))
        tail-item (some #(when (#{:channel-tail :channel-delta} (:kind %)) %) (:items resolved))
        map-item (some #(when (= :channel-map (:kind %)) %) (:items resolved))
        pulse-entry (when channel (get-in app [:digests (:id channel)]))
        pulse-conv (:conversation pulse-entry)
        active-turns (if (= :pulse (:active-tab app))
                       (:turns pulse-conv)
                       (:turns conversation))
        last-asst (last (filter #(= :assistant (:role %)) active-turns))
        turn-suggestions (:suggestions last-asst)
        contextual-suggs (when channel
                           (ctx/contextual-suggestions channel (:summary resolved) (:pinned selection)))
        active-suggestions (if (seq turn-suggestions) turn-suggestions contextual-suggs)
        all-sessions (session/merge-session-summaries (local-session-list app) (:remote-sessions app))
        filtered-sessions (if (and (:channel-only-sessions? app) channel)
                            (filterv #(or (= (str (:channel-id %)) (str (:id channel)))
                                          (= (str (:channel-name %)) (str (:name channel))))
                                     all-sessions)
                            all-sessions)
        update-selection! (fn [f & args]
                            (swap! app* update-in [:selections (:id channel)]
                                   #(apply f (or % (ctx/empty-selection channel)) args))
                            (render-soon! app*))
        chips-vec (when selection
                    (cond-> []
                      map-item
                      (conj {:label (:label map-item)
                             :on-click #(do (swap! app* assoc :active-tab :pulse)
                                            (render-soon! app*))})
                      :always
                      (conj {:label (str (if (:tail-enabled? selection) "✓ " "")
                                         (if tail-item
                                           (:label tail-item)
                                           (str "#" (:name channel) " · 0/" (:tail-limit selection))))
                             :off? (not (:tail-enabled? selection))
                             :on-click #(update-selection! ctx/toggle-tail)})
                      :always
                      (into (map (fn [m]
                                   (let [grabbed? (:grabbed? m)
                                         cross-srv? (= "cross-server" (:scope m))
                                         ch-lbl (when (seq (:channel-name m)) (str "#" (:channel-name m)))
                                         srv-lbl (when (and cross-srv? (seq (:guild-name m)))
                                                   (str (truncate (:guild-name m) 12) "/"))
                                         chip-label (if grabbed?
                                                      (str (if cross-srv? "🌐 " "🔗 ")
                                                           (or (when ch-lbl (str srv-lbl ch-lbl))
                                                               (truncate (:author m) 18))
                                                           ": "
                                                           (truncate (or (:preview m) (:text m)) 26))
                                                      (str "📌 " (truncate (:author m) 16) ": " (truncate (:text m) 28)))]
                                     {:label chip-label
                                      :removable? true
                                      :on-click #(update-selection! ctx/unpin-message (:event-id m))}))
                                 (:pinned selection)))))]
    {:open? (:open? app)
     :active-tab (:active-tab app)
     :auto-pulse? (:auto-pulse? app)
     :sessions-open? (:sessions-open? app)
     :channel-only-sessions? (:channel-only-sessions? app)
     :sessions filtered-sessions
     :active-conversation-id (:conversation-id conversation)
     :on-select-session #(select-session! app* %)
     :channel channel
     :channel-summary (:summary resolved)
     :pulse-conv pulse-conv
     :graph-state (:graph-state app)
     :connected? (:connected? app)
     :lens-config (:lens-config app)
     :running? (or (session/running? conversation) (session/running? pulse-conv))
     :turns (:turns conversation)
     :over-budget? (or (:over-budget? resolved) (pos? (:dropped resolved 0)))
     :budget-text (if resolved
                    (str "≈ " (ctx/estimate-tokens (:chars resolved)) " tokens"
                         " · " (get-in resolved [:summary :message-count] 0) " cached msgs"
                         " · budget ≈ " (ctx/estimate-tokens (budget-chars))
                         (when (pos? (count (:sent-event-ids conversation)))
                           (str " · delta mode (" (count (:sent-event-ids conversation)) " prior msgs indexed)"))
                         (when (:over-budget? resolved) " · pinned exceed budget"))
                    "Open a channel to select context.")
     :chips chips-vec
     :chips-list chips-vec
     :active-suggestions active-suggestions
     :suggestions-from-turn? (boolean (seq turn-suggestions))
     :on-use-suggestion #(use-suggestion! app* %1 %2)
     :on-stage-draft #(stage-draft! app* %)
     :on-approve-draft #(approve-draft! app* %1 %2)
     :on-toggle-auto-pulse #(let [next-val (not (:auto-pulse? @app*))]
                              (swap! app* assoc :auto-pulse? next-val)
                              (bd/data-save! plugin-name "autoPulse" (str next-val))
                              (when next-val (trigger-pulse! app* false))
                              (render-soon! app*))
     :on-refresh-pulse #(trigger-pulse! app* true)
     :on-promote-pulse #(promote-pulse! app*)
     :on-explore-graph #(explore-in-graph! app* %)
     :on-graph-search #(trigger-graph-query! app* %)
     :on-select-graph-node (fn [node]
                             (swap! app* assoc-in [:graph-state :selected-node-id] (:id node))
                             (render-soon! app*))
     :on-pin-graph-node #(pin-graph-node! app* %1 %2)
     :conn-graph (:conn-graph app)
     :extract-state (:extract-state app)
     :desktop-windows (:desktop-windows app)
     :telemetry (:telemetry app)
     :channel-buffer (when channel (str/join "\n" (map #(str (:author %) ": " (:text %)) (take-last 25 (channel-messages-for app (:id channel))))))
     :on-conn-query #(trigger-connections-query! app* %)
     :on-conn-refresh #(trigger-connections-query! app* %)
     :on-conn-node-click #(on-conn-node-click! app* %)
     :on-desktop-focus #(trigger-desktop-focus! app* %)
     :on-desktop-refresh #(trigger-desktop-windows-list! app*)
     :on-pin-window #(pin-desktop-window! app* %)
     :on-govone-extract #(trigger-govone-extract! app* %)
     :on-govone-send #(trigger-govone-channel-send! app* %)
     :on-govone-refresh-channel #(trigger-govone-channel-read! app*)}))

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
;; Actions: Sessions, Graph, Pulse, Chat
;; ─────────────────────────────────────────────────────────────

(defn- request-remote-sessions! [app*]
  (let [{:keys [socket-state connected?]} @app*]
    (when (and socket-state connected?)
      (socket/emit! socket-state proto/lens-sessions-list-to-bot #js {:limit 40}))))

(defn- request-remote-session-detail! [app* conversation-id]
  (let [{:keys [socket-state connected?]} @app*]
    (when (and socket-state connected? (seq (str conversation-id)))
      (socket/emit! socket-state proto/lens-session-get-to-bot #js {:sessionId (str conversation-id)}))))

(defn- messages-fingerprint [messages]
  (str (count messages) ":" (:event-id (first messages)) ":" (:event-id (last messages))))

(defn- request-channel-topics! [app*]
  (let [{:keys [socket-state connected?] :as app} @app*
        channel (current-channel app)
        ch-id (:id channel)
        messages (when ch-id (channel-messages-for app ch-id))
        fp (when (seq messages) (messages-fingerprint messages))
        prev-fp (when ch-id (get-in app [:channel-topics ch-id :requested-fingerprint]))]
    (when (and socket-state connected? ch-id (seq messages) (not= fp prev-fp))
      (swap! app* assoc-in [:channel-topics ch-id :requested-fingerprint] fp)
      (socket/emit! socket-state proto/lens-topics-extract-to-bot
                    (clj->js {:channelId ch-id
                              :fingerprint fp
                              :maxTopics 10
                              :messages (mapv #(select-keys % [:author :text :ts])
                                              (take-last 80 messages))})))))

(defn- schedule-topics-extract! [app*]
  (when-let [t (:topics-timer @app*)]
    (js/clearTimeout t))
  (let [timer (js/setTimeout
               (fn []
                 (swap! app* assoc :topics-timer nil)
                 (request-channel-topics! app*))
               220)]
    (swap! app* assoc :topics-timer timer)))

(defn- trigger-graph-query! [app* raw-query]
  (let [app @app*
        channel (current-channel app)
        messages (when channel (channel-messages-for app (:id channel)))
        summary (when (seq messages)
                  (ctx/extract-channel-summary messages (effective-channel-topics app (:id channel))))
        top-term (let [t (first (:top-terms summary))]
                   (if (map? t) (:term t) t))
        q (str/trim (str (or (not-empty (str raw-query))
                             top-term
                             (:name channel)
                             "orbit")))]
    (if-not (:connected? app)
      (bd/toast! "KnoxxLens: bot is offline" "error")
      (let [req-id (str (random-uuid))]
        (swap! app* update :graph-state
               #(assoc % :loading? true :query q :error nil))
        (render-soon! app*)
        (socket/emit! (:socket-state app) proto/lens-graph-query-to-bot
                      (clj->js {:requestId req-id
                                :query q
                                :channelId (:id channel)
                                :channelName (:name channel)
                                :limit 36}))))))

(defn- explore-in-graph! [app* query]
  (swap! app* assoc :active-tab :graph)
  (trigger-graph-query! app* query))

(defn- trigger-connections-query! [app* query]
  (let [app @app*]
    (if-not (:connected? app)
      (bd/toast! "KnoxxLens: bot is offline" "error")
      (let [q (str/trim (str (or query (get-in app [:conn-graph :query]) "")))]
        (swap! app* update :conn-graph #(assoc % :loading? true :query q :error nil))
        (render-soon! app*)
        (socket/emit! (:socket-state app) proto/lens-connections-to-bot
                      (clj->js {:query q
                                :maxNodes 50
                                :includeDesktop true}))))))

(defn- on-conn-node-click! [app* node]
  (swap! app* assoc-in [:conn-graph :selected-node] node)
  (render-soon! app*))

(defn- trigger-desktop-windows-list! [app*]
  (let [app @app*]
    (when (:connected? app)
      (socket/emit! (:socket-state app) proto/lens-desktop-list-to-bot #js {}))))

(defn- trigger-desktop-focus! [app* id-or-wid]
  (let [app @app*]
    (when (:connected? app)
      (socket/emit! (:socket-state app) proto/lens-desktop-focus-to-bot (clj->js {:id id-or-wid})))))

(defn- pin-desktop-window! [app* win]
  (when-let [channel (current-channel @app*)]
    (let [node-id (or (:id win) (str "window:" (:class win) ":" (:wid win)))
          msg {:id node-id
               :author (str "Desktop Window (" (or (:class win) "App") ")")
               :preview (str (or (:title win) "Window") " [WS " (or (:workspace win) "1") "]")
               :text (str "Desktop Window: " (or (:title win) "")
                          "\nClass: " (or (:class win) "")
                          "\nWorkspace: " (or (:workspace win) "")
                          (when (seq (str (:ocrText win)))
                            (str "\nNPU OCR Text:\n" (:ocrText win))))
               :pinned? true
               :pinned-at (.toISOString (js/Date.))
               :source "desktop-window"}]
      (swap! app* update-in [:selections (:id channel)]
             #(ctx/pin-message (or % (ctx/empty-selection channel)) msg))
      (bd/toast! (str "Pinned " (or (:class win) "window") " into context") "success")
      (render-soon! app*))))

(defn- trigger-govone-extract! [app* text]
  (let [app @app*]
    (if-not (:connected? app)
      (bd/toast! "KnoxxLens: bot is offline" "error")
      (do
        (swap! app* update :extract-state #(assoc % :loading? true :text text :error nil))
        (render-soon! app*)
        (socket/emit! (:socket-state app) proto/lens-govone-extract-to-bot (clj->js {:text text}))))))

(defn- trigger-govone-channel-send! [app* content]
  (let [app @app*]
    (when (:connected? app)
      (socket/emit! (:socket-state app) proto/lens-govone-channel-send-to-bot (clj->js {:content content})))))

(defn- trigger-govone-channel-read! [app*]
  (let [app @app*]
    (when (:connected? app)
      (socket/emit! (:socket-state app) proto/lens-govone-channel-read-to-bot #js {}))))

(declare send!)

(defn- pin-graph-node! [app* node incident-edges]
  (when-let [channel (current-channel @app*)]
    (let [msg (ctx/graph-node->context-message node incident-edges channel)]
      (swap! app* update-in [:selections (:id channel)]
             #(ctx/pin-message (or % (ctx/empty-selection channel)) msg))
      (bd/toast! (str "Pinned " (or (:label node) (:id node)) " to Chat context") "success")
      (render-soon! app*))))

(defn- use-suggestion! [app* suggestion shift-key?]
  (let [s (str/trim (str (or suggestion "")))]
    (when (seq s)
      (if shift-key?
        (do
          (swap! app* assoc :active-tab :chat)
          (render! app*)
          (some-> (:refs @app*) (panel/set-input! s)))
        (send! app* s)))))

(defn- stage-draft! [app* text]
  (let [app @app*
        channel (current-channel app)]
    (discord/insert-into-composer! (:stores app) (:id channel) text)
    (bd/toast! (str "Staged draft into " (if channel (str "#" (:name channel)) "Discord") " composer (& copied)") "success")))

(defn- approve-draft! [app* _draft-id text]
  (let [app @app*
        channel (current-channel app)
        res (discord/send-approved-message! (:stores app) (:id channel) text)]
    (cond
      (not (:ok res))
      (bd/toast! (str "Draft approval error: " (:error res)) "error")

      (= :sent (:mode res))
      (bd/toast! (str "Approved & sent to #" (:name channel)) "success")

      :else
      (bd/toast! (str "Staged in #" (:name channel) " composer — press Enter in Discord to send") "info"))))

(defn- trigger-pulse! [app* force?]
  (let [app @app*
        channel (current-channel app)
        ready? (and (:connected? app) (get-in app [:lens-config :ready]))]
    (when (and channel ready?)
      (let [ch-id (:id channel)
            messages (channel-messages-for app ch-id)
            msg-count (count messages)
            existing (get-in app [:digests ch-id])
            pulse-conv (:conversation existing)
            chat-conv (get-in app [:conversations ch-id])
            busy? (or (session/running? pulse-conv) (session/running? chat-conv))
            now-ms (.now js/Date)
            stale? (or (nil? existing)
                       (empty? (:turns pulse-conv))
                       (and (>= (- msg-count (:last-msg-count existing 0)) 8)
                            (> (- now-ms (:last-pulsed-ms existing 0)) 45000)))]
        (when (and (pos? msg-count)
                   (not busy?)
                   (or force? (and (:auto-pulse? app) stale?)))
          (when force?
            (swap! app* assoc :active-tab :pulse))
          (let [{:keys [items]} (resolve-current app channel {:include-map? true :query "decisions threads architecture blocker status"})
                sent-ids (mapcat :event-ids items)
                fresh-conv (-> (session/new-conversation (str (random-uuid)))
                               (assoc :channel-id ch-id
                                      :channel-name (:name channel)
                                      :title (str "⚡ Pulse · #" (:name channel))
                                      :updated-at (.toISOString (js/Date.))))
                request-id (str (random-uuid))
                started-conv (session/begin-turn fresh-conv
                                                 request-id
                                                 (str "⚡ Channel Pulse · #" (:name channel))
                                                 (mapv :label items)
                                                 sent-ids)]
            (swap! app* (fn [a]
                          (-> a
                              (assoc-in [:digests ch-id] {:conversation started-conv
                                                          :last-msg-count msg-count
                                                          :last-pulsed-ms now-ms})
                              (assoc-in [:requests request-id] {:target :pulse :channel-id ch-id}))))
            (socket/emit! (:socket-state app) proto/lens-ask-to-bot
                          (clj->js {:requestId request-id
                                    :conversationId (:conversation-id started-conv)
                                    :sessionId (:session-id started-conv)
                                    :mode "digest"
                                    :message (turn/digest-prompt channel)
                                    :channel channel
                                    :items (mapv #(select-keys % [:kind :label :text :event-ids :images]) items)}))
            (render-soon! app*)))))))

(defn- schedule-auto-pulse! [app*]
  (let [{:keys [pulse-timer open? auto-pulse?]} @app*]
    (when pulse-timer (js/clearTimeout pulse-timer))
    (when open?
      (let [timer (js/setTimeout
                   (fn []
                     (swap! app* assoc :pulse-timer nil)
                     (when-let [ch (current-channel @app*)]
                       (when (or (not= (:id ch) (:graph-channel-id @app*))
                                 (empty? (get-in @app* [:graph-state :nodes])))
                         (swap! app* assoc :graph-channel-id (:id ch))
                         (trigger-graph-query! app* nil)))
                     (when auto-pulse?
                       (trigger-pulse! app* false)))
                   1600)]
        (swap! app* assoc :pulse-timer timer)))))

(defn- promote-pulse! [app*]
  (when-let [channel (current-channel @app*)]
    (let [ch-id (:id channel)
          pulse-conv (get-in @app* [:digests ch-id :conversation])
          current-conv (get-in @app* [:conversations ch-id])]
      (when (and pulse-conv (seq (:turns pulse-conv)))
        (swap! app* (fn [a]
                      (-> a
                          (update :session-history archive-conversation
                                  (when (and current-conv (seq (:turns current-conv)))
                                    (assoc current-conv :channel-id ch-id :channel-name (:name channel))))
                          (assoc-in [:conversations ch-id]
                                    (assoc pulse-conv
                                           :channel-id ch-id
                                           :channel-name (:name channel)
                                           :updated-at (.toISOString (js/Date.))))
                          (assoc :active-tab :chat))))
        (persist-conversations! app*)
        (render! app*)
        (some-> (get-in @app* [:refs :input]) .focus)))))

(defn- switch-tab! [app* tab]
  (swap! app* assoc :active-tab tab)
  (bd/data-save! plugin-name "activeTab" (name tab))
  (case tab
    :pulse (let [ch (current-channel @app*)
                 pulse-conv (when ch (get-in @app* [:digests (:id ch) :conversation]))]
             (when (and ch (empty? (:turns pulse-conv)) (not (session/running? pulse-conv)))
               (trigger-pulse! app* true)))
    :graph (when (empty? (get-in @app* [:graph-state :nodes]))
             (trigger-graph-query! app* nil))
    :connections (when (empty? (get-in @app* [:conn-graph :nodes]))
                   (trigger-connections-query! app* nil))
    :extract (trigger-govone-channel-read! app*)
    :windows (trigger-desktop-windows-list! app*)
    nil)
  (render! app*))

(defn- set-open! [app* open?]
  (swap! app* assoc :open? open?)
  (bd/data-save! plugin-name "drawerOpen" (boolean open?))
  (when open?
    (request-channel-topics! app*)
    (request-remote-sessions! app*)
    (schedule-auto-pulse! app*))
  (render! app*)
  (when (and open? (= :chat (:active-tab @app*)))
    (some-> (get-in @app* [:refs :input]) .focus)))

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
                                :sessions-open? false
                                :active-tab :chat)
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
    ;; If user sends a message while viewing Pulse, promote Pulse into Chat first!
    (when (and channel (= :pulse (:active-tab app)))
      (let [pulse-conv (get-in app [:digests (:id channel) :conversation])]
        (when (and pulse-conv (seq (:turns pulse-conv)) (not (session/running? pulse-conv)))
          (promote-pulse! app*))))
    (let [app @app*]
      (cond
        (nil? channel) (bd/toast! "Open a channel first" "warning")
        (str/blank? text) (render! app*)
        (not (:connected? app)) (bd/toast! "KnoxxLens: bot is offline" "error")
        (session/running? (conversation-for app (:id channel))) (bd/toast! "Knoxx is still answering" "warning")
        :else
        (let [conversation (-> (conversation-for app (:id channel))
                               (assoc :channel-id (:id channel)
                                      :channel-name (:name channel)
                                      :updated-at (.toISOString (js/Date.))))
              {:keys [items]} (resolve-current app channel
                                               {:query text
                                                :include-map? true
                                                :already-sent-ids (:sent-event-ids conversation)})
              sent-ids (mapcat :event-ids items)
              request-id (str (random-uuid))
              started-conv (session/begin-turn conversation request-id text (mapv :label items) sent-ids)]
          (swap! app* (fn [a]
                        (-> a
                            (assoc :active-tab :chat)
                            (assoc-in [:conversations (:id channel)] started-conv)
                            (assoc-in [:requests request-id] {:target :chat :channel-id (:id channel)}))))
          (persist-conversations! app*)
          (socket/emit! (:socket-state app) proto/lens-ask-to-bot
                        (clj->js {:requestId request-id
                                  :conversationId (:conversation-id conversation)
                                  :sessionId (:session-id conversation)
                                  :message text
                                  :channel channel
                                  :items (mapv #(select-keys % [:kind :label :text :event-ids :images]) items)}))
          (panel/clear-input! (:refs app))
          (render! app*))))))

(defn- stop-turn! [app*]
  (when-let [channel (current-channel @app*)]
    (let [chat-conv (get-in @app* [:conversations (:id channel)])
          pulse-conv (get-in @app* [:digests (:id channel) :conversation])]
      (when (session/running? chat-conv)
        (socket/emit! (:socket-state @app*) proto/lens-abort-to-bot
                      #js {:conversationId (:conversation-id chat-conv)}))
      (when (session/running? pulse-conv)
        (socket/emit! (:socket-state @app*) proto/lens-abort-to-bot
                      #js {:conversationId (:conversation-id pulse-conv)})))))

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
                            (assoc :sessions-open? false
                                   :active-tab :chat))))
          (persist-conversations! app*)
          (render! app*))))))

(defn- pin-from-menu! [app* message channel-id]
  (let [{:keys [stores]} @app*
        channel (discord/channel-info stores channel-id)
        context-message (discord/message->context stores message channel-id)]
    (swap! app* update-in [:selections channel-id] #(ctx/pin-message (or % (ctx/empty-selection channel)) context-message))
    (bd/toast! "Added to Knoxx context" "success")
    (set-open! app* true)))

(defn- latest-refined-topics [turns]
  (some :refined-topics (reverse (or turns []))))

(defn- turn-grabbed-context [turns request-id]
  (some #(when (= request-id (:request-id %)) (:grabbed-context %))
        (reverse (or turns []))))

(defn- on-reply [app* kind]
  (fn [data]
    (let [reply (js->clj data :keywordize-keys true)
          request-id (:requestId reply)]
      (case kind
        :token (swap! app* assoc-in [:telemetry :phase] :streaming)
        :event (let [etype (:type reply)]
                 (cond
                   (= "tool_start" etype)
                   (swap! app* update :telemetry #(assoc % :phase :tool :tool-name (or (:tool reply) (:name reply) "tool")))
                   (= "tool_end" etype)
                   (swap! app* assoc-in [:telemetry :phase] :thinking)
                   (or (= "run_completed" etype) (= "done" etype))
                   (swap! app* update :telemetry
                          #(assoc % :phase :idle
                                    :ttft-ms (or (:ttft_ms reply) (:ttft-ms %))
                                    :tokens-per-s (or (:tokens_per_s reply) (:tokens-per-s %))
                                    :input-tokens (or (:input_tokens reply) (:input-tokens %))
                                    :output-tokens (or (:output_tokens reply) (:output-tokens %))))))
        (:done :error) (swap! app* assoc-in [:telemetry :phase] :idle)
        nil)
      (when-let [req-meta (get-in @app* [:requests request-id])]
        (let [{:keys [target channel-id]} (if (map? req-meta)
                                            req-meta
                                            {:target :chat :channel-id req-meta})
              path (if (= :pulse target)
                     [:digests channel-id :conversation]
                     [:conversations channel-id])]
          (swap! app*
                 (fn [a]
                   (let [next-conv (-> (session/apply-reply (get-in a path) kind request-id reply)
                                       (assoc :updated-at (.toISOString (js/Date.))))
                         refined (latest-refined-topics (:turns next-conv))
                         grabbed (turn-grabbed-context (:turns next-conv) request-id)
                         ch-info (when (and channel-id (:stores a))
                                   (discord/channel-info (:stores a) channel-id))]
                     (cond-> (assoc-in a path next-conv)
                       (and channel-id (seq refined))
                       (assoc-in [:channel-topics channel-id :agent-topics] refined)

                       (and channel-id (seq grabbed))
                       (update-in [:selections channel-id]
                                  #(ctx/pin-grabbed-items
                                    (or % (ctx/empty-selection (or ch-info {:id channel-id})))
                                    grabbed))))))
          (when (#{:done :error} kind)
            (swap! app* update :requests dissoc request-id)
            (when (= :chat target)
              (persist-conversations! app*)
              (request-remote-sessions! app*)))
          (when (and (= :accepted kind) (= :chat target))
            (persist-conversations! app*))
          (when (= :error kind)
            (bd/toast! (str "Knoxx: " (:error reply)) "error"))
          (render-soon! app*))))))

(defn- on-connections-reply [app*]
  (fn [data]
    (let [res (js->clj data :keywordize-keys true)]
      (swap! app* update :conn-graph
             (fn [cg]
               (if (:ok res)
                 (assoc cg
                        :loading? false
                        :nodes (vec (or (:nodes res) []))
                        :edges (vec (or (:edges res) []))
                        :people-bridges (vec (or (:peopleBridges res) []))
                        :timeframe (:timeframe res)
                        :error nil)
                 (assoc cg :loading? false :error (:error res)))))
      (render-soon! app*))))

(defn- on-govone-extract-reply [app*]
  (fn [data]
    (let [res (js->clj data :keywordize-keys true)]
      (swap! app* update :extract-state
             (fn [es]
               (if (:ok res)
                 (assoc es
                        :loading? false
                        :sanitized (or (:sanitized_text res) (:sanitized res) "")
                        :entities (vec (or (:entities res) []))
                        :merkle-root (:merkle_root res)
                        :dek-shredded (:dek_shredded res)
                        :error nil)
                 (assoc es :loading? false :error (:error res)))))
      (render-soon! app*))))

(defn- on-govone-channel-read-reply [app*]
  (fn [data]
    (let [res (js->clj data :keywordize-keys true)]
      (when (:ok res)
        (swap! app* assoc-in [:extract-state :messages] (vec (or (:messages res) []))))
      (render-soon! app*))))

(defn- on-desktop-list-reply [app*]
  (fn [data]
    (let [res (js->clj data :keywordize-keys true)]
      (when (:ok res)
        (swap! app* assoc :desktop-windows (vec (or (:windows res) []))))
      (render-soon! app*))))

(defn- on-desktop-focus-reply [_app*]
  (fn [data]
    (let [res (js->clj data :keywordize-keys true)]
      (if (:ok res)
        (bd/toast! "Focused window in i3" "success")
        (bd/toast! (str "Focus failed: " (:error res)) "error")))))

(defn- on-graph-reply [app*]
  (fn [data]
    (let [res (js->clj data :keywordize-keys true)]
      (if (:ok res)
        (let [nodes (vec (or (:nodes res) []))
              edges (vec (or (:edges res) []))]
          (swap! app* update :graph-state
                 (fn [gs]
                   (assoc gs
                          :loading? false
                          :query (or (:query res) (:query gs))
                          :nodes nodes
                          :edges edges
                          :clusters (vec (or (:clusters res) []))
                          :stats (:stats res)
                          :error nil
                          :selected-node-id (:id (first nodes))))))
        (swap! app* update :graph-state
               #(assoc % :loading? false :error (or (:error res) "Graph query failed"))))
      (render-soon! app*))))

(defn- on-topics-reply [app*]
  (fn [data]
    (let [res (js->clj data :keywordize-keys true)
          ch-id (str (or (:channelId res) ""))]
      (when (and (:ok res) (seq ch-id))
        (swap! app* update-in [:channel-topics ch-id]
               (fn [entry]
                 (assoc (or entry {})
                        :bot-topics (vec (or (:topics res) []))
                        :fingerprint (:fingerprint res)
                        :engine (:engine res))))
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
          turns (when (:ok res) (session/rows->turns (:rows res)))
          refined (latest-refined-topics turns)]
      (when (and (seq cid) (seq turns))
        (swap! app*
               (fn [a]
                 (let [matching-ch-ids (keep (fn [[ch-id conv]]
                                               (when (= cid (:conversation-id conv))
                                                 ch-id))
                                             (:conversations a))
                       updated-convs (into {}
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
                                                  :title (session/conversation-title (assoc hist-entry :turns turns))))
                       updated-topics (if (seq refined)
                                        (reduce (fn [tm ch-id]
                                                  (assoc-in tm [ch-id :agent-topics] refined))
                                                (:channel-topics a)
                                                matching-ch-ids)
                                        (:channel-topics a))]
                   (assoc a
                          :conversations updated-convs
                          :session-history updated-hist
                          :channel-topics updated-topics))))
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
                                                     (request-channel-topics! app*)
                                                     (request-remote-sessions! app*)
                                                     (trigger-desktop-windows-list! app*)
                                                     (trigger-govone-channel-read! app*)
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
                  (schedule-auto-pulse! app*)
                  (render-soon! app*)))
    (socket/on! socket-state proto/lens-sessions-list-to-plugin (on-sessions-list app*))
    (socket/on! socket-state proto/lens-session-get-to-plugin (on-session-detail app*))
    (socket/on! socket-state proto/lens-graph-query-to-plugin (on-graph-reply app*))
    (socket/on! socket-state proto/lens-topics-extract-to-plugin (on-topics-reply app*))
    (socket/on! socket-state proto/lens-connections-to-plugin (on-connections-reply app*))
    (socket/on! socket-state proto/lens-govone-extract-to-plugin (on-govone-extract-reply app*))
    (socket/on! socket-state proto/lens-govone-channel-read-to-plugin (on-govone-channel-read-reply app*))
    (socket/on! socket-state proto/lens-desktop-list-to-plugin (on-desktop-list-reply app*))
    (socket/on! socket-state proto/lens-desktop-focus-to-plugin (on-desktop-focus-reply app*))
    (socket/on! socket-state "bot:lens:ui"
                (fn [data]
                  (let [cmd (js->clj data :keywordize-keys true)]
                    (when (contains? cmd :open)
                      (set-open! app* (boolean (:open cmd))))
                    (when-let [t (:tab cmd)]
                      (switch-tab! app* (keyword t)))
                    (when-let [q (:graphQuery cmd)]
                      (explore-in-graph! app* q))
                    (when (:refreshPulse cmd)
                      (trigger-pulse! app* true))
                    (when-let [msg (:ask cmd)]
                      (send! app* msg)))))
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
  (let [{:keys [stores last-channel-id]} @app*]
    (when stores
      (when-let [channel-id (discord/action-channel-id action)]
        (harvest-channel! app* channel-id (discord/action-messages stores action))
        (harvest-channel! app* channel-id (discord/loaded-messages stores channel-id)))
      (harvest-open-channel! app*)
      (let [now-ch-id (discord/selected-channel-id stores)]
        (when (and now-ch-id (not= now-ch-id last-channel-id))
          (swap! app* assoc :last-channel-id now-ch-id))
        (when (:open? @app*)
          (schedule-topics-extract! app*)
          (schedule-auto-pulse! app*)
          (render-soon! app*))))))

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
                              :on-tab-change #(switch-tab! app* %)
                              :on-history-toggle #(toggle-history! app*)
                              :on-history-filter #(toggle-history-filter! app*)
                              :on-history-refresh #(request-remote-sessions! app*)
                              :on-send #(send! app* %)
                              :on-stop #(stop-turn! app*)}))
        keydown (keybind-handler app*)
        on-action #(on-discord-action! app* %)]
    (let [convs (load-conversations)
          initial-topics (into {}
                               (keep (fn [[ch-id conv]]
                                       (when-let [rt (latest-refined-topics (:turns conv))]
                                         [ch-id {:agent-topics rt}])))
                               convs)]
      (swap! app* assoc
             :stores stores
             :refs refs
             :open? (load-drawer-open)
             :active-tab (load-active-tab)
             :auto-pulse? (load-auto-pulse-setting)
             :last-channel-id (when stores (discord/selected-channel-id stores))
             :conversations convs
             :channel-topics initial-topics
             :session-history (load-session-history)))
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
  (when-let [t (:pulse-timer @app*)] (js/clearTimeout t))
  (when-let [t (:topics-timer @app*)] (js/clearTimeout t))
  (doseq [cleanup (:cleanup @app*)]
    (try (cleanup) (catch :default err (bd/log! "warn" plugin-name "cleanup failed" err))))
  (when-let [refs (:refs @app*)] (panel/unmount! refs))
  (when-let [socket-state (:socket-state @app*)] (socket/disconnect! socket-state))
  (persist-conversations! app*)
  (swap! app* assoc :started? false :pulse-timer nil :topics-timer nil :cleanup [] :refs nil :socket-state nil :connected? false)
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
