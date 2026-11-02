(ns bitch-tracker.bot.lens
  "KnoxxLens broker: receives plugin asks over socket.io, runs Knoxx direct turns,
  and relays the stream back to the asking socket only. Orchestration; policy
  lives in domain.lens-* and law.lens."
  (:require [bitch-tracker.bot.desktop :as desktop]
            [bitch-tracker.bot.govone :as govone]
            [bitch-tracker.bot.knoxx :as knoxx]
            [bitch-tracker.bot.openplanner :as op]
            [bitch-tracker.bot.topics :as bot-topics]
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
     :mode (str (or (:mode ask) "direct"))
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
                                               :mode (:mode ask)
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

(defn ^:async handle-graph-query!
  "Queries OpenPlanner's knowledge graph (/v1/graph/memory) and emits nodes + edges to the asking socket."
  [config ^js socket raw]
  (let [req (js->clj (or raw #js {}) :keywordize-keys true)
        q (str/trim (str (or (:query req) (:q req) "")))
        lakes (:lakes req)
        node-types (:nodeTypes req)
        k (:k req 12)]
    (if (str/blank? q)
      (emit! socket proto/lens-graph-query-to-plugin
             {:ok false :query "" :error "Query is required" :nodes [] :edges []})
      (try
        (let [res (await (op/query-graph-memory! config {:q q :k k :lakes lakes :node-types node-types :max-nodes 40}))]
          (if (:ok res)
            (emit! socket proto/lens-graph-query-to-plugin
                   {:ok true
                    :query q
                    :nodes (vec (or (get-in res [:body :nodes]) []))
                    :edges (vec (or (get-in res [:body :edges]) []))
                    :clusters (vec (or (get-in res [:body :clusters]) []))
                    :stats (or (get-in res [:body :stats]) {})})
            (emit! socket proto/lens-graph-query-to-plugin
                   {:ok false
                    :query q
                    :error (str "OpenPlanner HTTP " (:status res) ": "
                                (or (get-in res [:body :error]) "graph query failed"))
                    :nodes []
                    :edges []})))
        (catch :default err
          (emit! socket proto/lens-graph-query-to-plugin
                 {:ok false :query q :error (str "Graph query error: " (.-message err)) :nodes [] :edges []}))))))

(defn ^:async handle-topics-extract!
  "Extracts ranked noun & proper-noun topics using neural NER + Penn Treebank POS
   tagger and emits them back to the asking plugin socket."
  [^js socket raw]
  (let [req (js->clj (or raw #js {}) :keywordize-keys true)
        channel-id (str (or (:channelId req) ""))
        fingerprint (str (or (:fingerprint req) ""))
        messages (vec (or (:messages req) []))
        max-topics (:maxTopics req 10)]
    (if (empty? messages)
      (emit! socket proto/lens-topics-extract-to-plugin
             {:ok true :channelId channel-id :fingerprint fingerprint :topics []})
      (try
        (let [res (await (bot-topics/extract-channel-topics!
                          {:channel-id channel-id
                           :fingerprint fingerprint
                           :messages messages
                           :max-topics max-topics}))]
          (emit! socket proto/lens-topics-extract-to-plugin res))
        (catch :default err
          (emit! socket proto/lens-topics-extract-to-plugin
                 {:ok false
                  :channelId channel-id
                  :fingerprint fingerprint
                  :error (str "Topic extraction error: " (.-message err))
                  :topics []}))))))

(defn ^:async handle-connections-get!
  "Fetches WebGL connections graph from OpenPlanner and emits/acknowledges to the asking plugin socket."
  [config ^js socket raw ack]
  (let [req (js->clj (or raw #js {}) :keywordize-keys true)
        q (str (or (:query req) (:q req) ""))
        lakes (:lakes req)
        max-nodes (:maxNodes req (:max_nodes req 50))
        include-desktop (if (some? (:includeDesktop req)) (:includeDesktop req) true)
        seed-node-ids (:seedNodeIds req (:seed_node_ids req []))]
    (try
      (let [res (await (op/query-graph-connections!
                        config
                        {:query q
                         :lakes lakes
                         :max-nodes max-nodes
                         :include-desktop include-desktop
                         :seed-node-ids seed-node-ids}))
            payload (if (:ok res)
                      (assoc (:body res) :ok true)
                      {:ok false :error (or (get-in res [:body :error]) (str "HTTP " (:status res)))})]
        (emit! socket proto/lens-connections-to-plugin payload)
        (when (fn? ack) (ack (clj->js payload))))
      (catch :default err
        (let [err-payload {:ok false :error (.-message err)}]
          (emit! socket proto/lens-connections-to-plugin err-payload)
          (when (fn? ack) (ack (clj->js err-payload))))))))

(defn ^:async handle-govone-extract!
  "Performs GovOne de-identification on input text, returning [[ENTITY_N]] masked text,
   audit Merkle proof, and DEK shredding confirmation."
  [config ^js socket raw ack]
  (let [req (js->clj (or raw #js {}) :keywordize-keys true)
        text (str (or (:text req) (:content req) ""))
        payload (if (str/blank? text)
                  {:ok false :error "text is required"}
                  (await (govone/extract-and-sanitize! config text)))]
    (emit! socket proto/lens-govone-extract-to-plugin payload)
    (when (fn? ack) (ack (clj->js payload)))))

(defn ^:async handle-govone-channel-read!
  "Reads messages from the GovOne governed channel."
  [config ^js socket _raw ack]
  (let [payload (await (govone/read-channel-messages! config))]
    (emit! socket proto/lens-govone-channel-read-to-plugin payload)
    (when (fn? ack) (ack (clj->js payload)))))

(defn ^:async handle-govone-channel-send!
  "Sends a message over the GovOne governed channel."
  [config ^js socket raw ack]
  (let [req (js->clj (or raw #js {}) :keywordize-keys true)
        content (str (or (:content req) (:message req) ""))
        payload (if (str/blank? content)
                  {:ok false :error "content is required"}
                  (await (govone/send-channel-message! config content (:priority req "normal"))))]
    (emit! socket proto/lens-govone-channel-send-to-plugin payload)
    (when (fn? ack) (ack (clj->js payload)))))

(defn register!
  "Wires KnoxxLens socket handlers onto a newly connected plugin socket."
  [runs config ^js socket]
  (desktop/register-handlers! socket)
  (.on socket proto/lens-config-request-to-bot
       (fn [_] (emit! socket proto/lens-config-response-to-plugin (lens-config config))))
  (.on socket proto/lens-ask-to-bot (fn [data] (handle-ask! runs config socket data)))
  (.on socket proto/lens-abort-to-bot (fn [data] (handle-abort! runs config socket data)))
  (.on socket proto/lens-sessions-list-to-bot (fn [data] (handle-list-sessions! config socket data)))
  (.on socket proto/lens-session-get-to-bot (fn [data] (handle-get-session! config socket data)))
  (.on socket proto/lens-graph-query-to-bot (fn [data] (handle-graph-query! config socket data)))
  (.on socket proto/lens-topics-extract-to-bot (fn [data] (handle-topics-extract! socket data)))
  (.on socket proto/lens-connections-to-bot (fn [data ack] (handle-connections-get! config socket data ack)))
  (.on socket proto/lens-govone-extract-to-bot (fn [data ack] (handle-govone-extract! config socket data ack)))
  (.on socket proto/lens-govone-channel-read-to-bot (fn [data ack] (handle-govone-channel-read! config socket data ack)))
  (.on socket proto/lens-govone-channel-send-to-bot (fn [data ack] (handle-govone-channel-send! config socket data ack)))
  (.on socket "lens:ui:broadcast" (fn [data] (.emit (.-broadcast socket) "bot:lens:ui" data)))
  (.on socket "disconnect" (fn [] (handle-disconnect! runs config socket))))

