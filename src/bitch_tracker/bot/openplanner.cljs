(ns bitch-tracker.bot.openplanner
  (:require [clojure.string :as str]
            [bitch-tracker.shared.util :as u]))

(defn- auth-header [api-key-or-header]
  (let [value (str/trim (str api-key-or-header))]
    (if (.test (js/RegExp. "^Bearer\\s+" "i") value)
      value
      (str "Bearer " value))))

(defn endpoint [config]
  (str (:openplanner-base-url config) "/v1/events"))

(defn make-state [_config]
  #js {"queue" #js []
       "pending_semantic_queries" (js/Set.)
       "flush_timer" nil
       "semantic_scan_timer" nil
       "flush_inflight" false})

(defn queue-event! [^js state event max-persisted]
  (when event
    (.push (.-queue state) event)
    (when (> (.-length (.-queue state)) max-persisted)
      (.splice (.-queue state) 0 (- (.-length (.-queue state)) max-persisted))))
  event)

(defn ^:async flush! [^js state config]
  (when (.-flush_inflight state) (js/Promise.resolve nil))
  (set! (.-flush_inflight state) true)
  (try
    (let [queue (.-queue state)
          api-key (:openplanner-api-key config)]
      (when (and (> (.-length queue) 0) (u/present-string? api-key))
        (let [batch (.slice queue 0 (:max-batch-size config))]
          (try
            (let [res (await (js/fetch (endpoint config)
                                       #js {:method "POST"
                                            :headers #js {"content-type" "application/json"
                                                          "authorization" (auth-header api-key)}
                                            :body (js/JSON.stringify #js {:events batch})}))]
              (if (.-ok res)
                (do
                  (.splice queue 0 (.-length batch))
                  (js/console.log "[openplanner] Sent event batch" (.-length batch)))
                (throw (js/Error. (str (.-status res) " " (.-statusText res))))))
            (catch :default err
              (js/console.warn "[openplanner] Flush failed; will retry" err))))))
    (finally
      (set! (.-flush_inflight state) false))))

(defn ^:async query-semantic-similar [text k config]
  (let [api-key (:openplanner-api-key config)
        base-url (:openplanner-base-url config)
        query-endpoint (str base-url "/v1/graph/similar")]
    (if (str/blank? api-key)
      #js []
      (try
        (let [res (await (js/fetch query-endpoint
                                   #js {:method "POST"
                                        :headers #js {"content-type" "application/json"
                                                      "authorization" (auth-header api-key)}
                                        :body (js/JSON.stringify
                                               #js {:q text
                                                    :k k
                                                    :where #js {:source "betterdiscord-openplanner"}})}))
              data (if (.-ok res) (await (.json res)) nil)
              hits (or (u/jget data "hits") #js [])]
          (.filter hits (fn [^js hit]
                          (>= (- 1 (or (u/jget hit "distance") 0)) 0.75))))
          (catch :default err
            (js/console.warn "[openplanner] Semantic query failed" err)
            #js [])))))

(defn ^:async run-semantic-scan! [^js state config]
  (let [pending (.-pending_semantic_queries state)]
    (when (> (.-size pending) 0)
      (let [api-key (:openplanner-api-key config)
            base-url (:openplanner-base-url config)]
        (when (u/present-string? api-key)
          (try
            (let [to-check (.slice (js/Array.from pending) 0 10)
                  event-ids (.map to-check (fn [id] (str "discord:discord.message:" id)))
                  res (await (js/fetch (str base-url "/v1/graph/node-embeddings/query")
                                       #js {:method "POST"
                                            :headers #js {"content-type" "application/json"
                                                          "authorization" (auth-header api-key)}
                                            :body (js/JSON.stringify #js {:eventIds event-ids})}))
                  data (when (.-ok res) (await (.json res)))
                  embedded (js/Set. (.map (or (u/jget data "vectors") #js []) (fn [^js v] (or (u/jget v "sourceEventId") (u/jget v "id")))))]
              (doseq [message-id (array-seq to-check)]
                (when (.has embedded (str "discord:discord.message:" message-id))
                  (.delete pending message-id))))
            (catch :default err
              (js/console.warn "[openplanner] Semantic scan failed" err))))))))

(defn start-timers! [^js state config]
  (let [flush-timer (js/setInterval #(flush! state config) (:flush-every-ms config))
        semantic-timer (js/setInterval #(run-semantic-scan! state config) (:semantic-scan-every-ms config))]
    (set! (.-flush_timer state) flush-timer)
    (set! (.-semantic_scan_timer state) semantic-timer)
    state))

(defn stop-timers! [^js state]
  (when (.-flush_timer state)
    (js/clearInterval (.-flush_timer state))
    (set! (.-flush_timer state) nil))
  (when (.-semantic_scan_timer state)
    (js/clearInterval (.-semantic_scan_timer state))
    (set! (.-semantic_scan_timer state) nil))
  state)
