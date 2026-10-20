(ns bitch-tracker.bot.lens
  "KnoxxLens broker: receives plugin asks over socket.io, runs Knoxx direct turns,
  and relays the stream back to the asking socket only. Orchestration; policy
  lives in domain.lens-* and law.lens."
  (:require [bitch-tracker.bot.knoxx :as knoxx]
            [bitch-tracker.domain.lens-stream :as stream]
            [bitch-tracker.domain.lens-turn :as turn]
            [bitch-tracker.law.lens :as law]
            [bitch-tracker.shape.protocol :as proto]
            [clojure.string :as str]))

(defn make-state
  "Returns the broker's run registry: conversation-id -> {:ws :timer :run-id :socket-id}."
  []
  (atom {}))

(defn- emit! [^js socket event-name data]
  (.emit socket event-name (clj->js data)))

(defn- finish!
  "Forgets the run for conversation-id, then releases its timer and stream."
  [runs conversation-id]
  (when-let [{:keys [ws timer]} (get @runs conversation-id)]
    (swap! runs dissoc conversation-id)
    (js/clearTimeout timer)
    (knoxx/close-stream! ws)))

(defn- current-run? [runs conversation-id run-id]
  (= run-id (:run-id (get @runs conversation-id))))

(defn- parse-ask [raw]
  (let [ask (js->clj raw :keywordize-keys true)]
    {:request-id (str (or (:requestId ask) ""))
     :conversation-id (str (or (:conversationId ask) ""))
     :session-id (str (or (:sessionId ask) ""))
     :message (str (or (:message ask) ""))
     :channel (or (:channel ask) {})
     :items (vec (or (:items ask) []))}))

(defn lens-config
  "Returns the KnoxxLens readiness payload for plugins. Contains no secrets."
  [config]
  (let [problems (law/config-problems config)]
    {:ready (empty? problems)
     :problems problems
     :model (:lens-model config)
     :knoxxBaseUrl (:knoxx-base-url config)}))

(defn- relay-actions! [runs conversation-id run-id reply actions]
  (doseq [[kind value] actions]
    (case kind
      :token (reply proto/lens-token-to-plugin {:token value})
      :event (reply proto/lens-event-to-plugin value)
      :done (do (reply proto/lens-done-to-plugin {:text value :runId run-id})
                (finish! runs conversation-id))
      :error (do (reply proto/lens-error-to-plugin {:error value :runId run-id})
                 (finish! runs conversation-id)))))

(defn ^:async handle-ask!
  "Validates an ask, opens the Knoxx stream for its conversation, then starts the turn."
  [runs config ^js socket raw]
  (let [{:keys [request-id conversation-id session-id message channel items] :as ask} (parse-ask raw)
        reply (fn [event-name data]
                (emit! socket event-name (assoc data :requestId request-id :conversationId conversation-id)))
        problems (into (law/config-problems config) (law/ask-problems ask))]
    (cond
      (seq problems)
      (reply proto/lens-error-to-plugin {:error (str/join "; " problems) :refused true})

      (contains? @runs conversation-id)
      (reply proto/lens-error-to-plugin {:error "A turn is already running for this conversation" :refused true})

      :else
      (let [run-id (str (random-uuid))
            run* (atom (stream/initial-run run-id))
            on-envelope (fn [envelope]
                          (let [{:keys [run actions]} (stream/step @run* envelope)]
                            (reset! run* run)
                            (relay-actions! runs conversation-id run-id reply actions)))
            on-close (fn []
                       (when (current-run? runs conversation-id run-id)
                         (reply proto/lens-error-to-plugin {:error "Knoxx stream closed before the turn finished" :runId run-id})
                         (finish! runs conversation-id)))]
        (try
          (let [ws (await (knoxx/open-stream! config conversation-id {:on-envelope on-envelope :on-close on-close}))
                timer (js/setTimeout
                       (fn []
                         (when (current-run? runs conversation-id run-id)
                           (reply proto/lens-error-to-plugin {:error "KnoxxLens turn timed out" :runId run-id})
                           (knoxx/abort-turn! config conversation-id "knoxx-lens timeout")
                           (finish! runs conversation-id)))
                       (:lens-turn-timeout-ms config))]
            (swap! runs assoc conversation-id {:ws ws :timer timer :run-id run-id :socket-id (.-id socket)})
            (let [res (await (knoxx/start-direct-turn!
                              config
                              (turn/turn-body {:message message
                                               :conversation-id conversation-id
                                               :session-id session-id
                                               :run-id run-id
                                               :model (:lens-model config)
                                               :channel channel
                                               :context {:items items}})))]
              (if (:ok res)
                (reply proto/lens-accepted-to-plugin {:runId run-id
                                                      :sessionId (get-in res [:body :session_id])
                                                      :model (get-in res [:body :model])})
                (do
                  (reply proto/lens-error-to-plugin
                         {:error (str "Knoxx refused the turn (HTTP " (:status res) "): "
                                      (or (get-in res [:body :error]) (get-in res [:body :raw]) "no detail"))
                          :runId run-id})
                  (finish! runs conversation-id)))))
          (catch :default err
            (reply proto/lens-error-to-plugin {:error (str "Knoxx unreachable: " (.-message err)) :runId run-id})
            (finish! runs conversation-id)))))))

(defn ^:async handle-abort!
  "Asks Knoxx to abort the running turn for the plugin's conversation."
  [runs config ^js socket raw]
  (let [conversation-id (str (or (some-> ^js raw .-conversationId) ""))]
    (when (contains? @runs conversation-id)
      (emit! socket proto/lens-event-to-plugin {:type "abort_requested" :conversationId conversation-id})
      (await (knoxx/abort-turn! config conversation-id "knoxx-lens user abort")))))

(defn handle-disconnect!
  "Aborts and forgets every run owned by a disconnected socket."
  [runs config ^js socket]
  (doseq [[conversation-id {:keys [socket-id]}] @runs]
    (when (= socket-id (.-id socket))
      (knoxx/abort-turn! config conversation-id "knoxx-lens client disconnected")
      (finish! runs conversation-id))))

(defn ^:async handle-list-sessions!
  "Lists past Knoxx sessions and emits them back to the asking plugin socket."
  [config ^js socket raw]
  (let [req (js->clj (or raw #js {}) :keywordize-keys true)
        problems (law/config-problems config)]
    (if (seq problems)
      (emit! socket proto/lens-sessions-list-to-plugin {:ok false :error (str/join "; " problems) :rows []})
      (try
        (let [res (await (knoxx/list-sessions! config {:limit (:limit req 30) :offset (:offset req 0)}))]
          (if (:ok res)
            (emit! socket proto/lens-sessions-list-to-plugin
                   {:ok true
                    :rows (vec (or (get-in res [:body :rows]) []))
                    :hasMore (boolean (get-in res [:body :has_more]))})
            (emit! socket proto/lens-sessions-list-to-plugin
                   {:ok false
                    :error (str "Knoxx HTTP " (:status res))
                    :rows []})))
        (catch :default err
          (emit! socket proto/lens-sessions-list-to-plugin
                 {:ok false :error (str "Knoxx unreachable: " (.-message err)) :rows []}))))))

(defn ^:async handle-get-session!
  "Fetches a single past Knoxx session's rows and emits them to the asking plugin socket."
  [config ^js socket raw]
  (let [req (js->clj (or raw #js {}) :keywordize-keys true)
        session-id (str (or (:sessionId req) (:conversationId req) ""))
        problems (law/config-problems config)]
    (cond
      (seq problems)
      (emit! socket proto/lens-session-get-to-plugin
             {:ok false :sessionId session-id :error (str/join "; " problems) :rows []})

      (str/blank? session-id)
      (emit! socket proto/lens-session-get-to-plugin
             {:ok false :sessionId "" :error "sessionId is required" :rows []})

      :else
      (try
        (let [res (await (knoxx/get-session! config session-id))]
          (if (:ok res)
            (emit! socket proto/lens-session-get-to-plugin
                   {:ok true
                    :sessionId session-id
                    :rows (vec (or (get-in res [:body :rows]) []))})
            (emit! socket proto/lens-session-get-to-plugin
                   {:ok false
                    :sessionId session-id
                    :error (str "Knoxx HTTP " (:status res))
                    :rows []})))
        (catch :default err
          (emit! socket proto/lens-session-get-to-plugin
                 {:ok false :sessionId session-id :error (str "Knoxx unreachable: " (.-message err)) :rows []}))))))

(defn register!
  "Wires KnoxxLens socket handlers onto a newly connected plugin socket."
  [runs config ^js socket]
  (.on socket proto/lens-config-request-to-bot
       (fn [_] (emit! socket proto/lens-config-response-to-plugin (lens-config config))))
  (.on socket proto/lens-ask-to-bot (fn [data] (handle-ask! runs config socket data)))
  (.on socket proto/lens-abort-to-bot (fn [data] (handle-abort! runs config socket data)))
  (.on socket proto/lens-sessions-list-to-bot (fn [data] (handle-list-sessions! config socket data)))
  (.on socket proto/lens-session-get-to-bot (fn [data] (handle-get-session! config socket data)))
  (.on socket "disconnect" (fn [] (handle-disconnect! runs config socket))))
