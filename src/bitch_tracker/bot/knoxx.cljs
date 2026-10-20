(ns bitch-tracker.bot.knoxx
  "Knoxx HTTP + WebSocket transport for KnoxxLens turns.
  All I/O; no domain policy. Uses Node's global fetch and WebSocket (Node >= 22)."
  (:require [bitch-tracker.shared.util :as u]))

(defn- headers [config]
  #js {"content-type" "application/json"
       "x-api-key" (:knoxx-api-key config)})

(defn- ws-url [config conversation-id]
  (let [url (js/URL. (str (:knoxx-base-url config) "/ws/stream"))]
    (set! (.-protocol url) (if (= "https:" (.-protocol url)) "wss:" "ws:"))
    (.set (.-searchParams url) "conversation_id" conversation-id)
    (str url)))

(defn ^:async post-json!
  "POSTs body (CLJS map) to path on Knoxx. Returns {:ok bool :status n :body clj-map}."
  [config path body]
  (let [res (await (js/fetch (str (:knoxx-base-url config) path)
                             #js {:method "POST"
                                  :headers (headers config)
                                  :body (js/JSON.stringify (clj->js body))}))
        text (await (.text res))
        parsed (u/parse-json text nil)]
    {:ok (.-ok res)
     :status (.-status res)
     :body (if parsed (js->clj parsed :keywordize-keys true) {:raw text})}))

(defn ^:async start-direct-turn!
  "Queues a direct (non-RAG) Knoxx turn. Resolves to post-json! result."
  [config body]
  (await (post-json! config "/api/knoxx/direct/start" body)))

(defn ^:async abort-turn!
  "Requests Knoxx abort the running turn for conversation-id. Never rejects:
  callers fire this from timers and disconnect handlers, where an unhandled
  rejection would crash the bot."
  [config conversation-id reason]
  (try
    (await (post-json! config "/api/knoxx/abort" {:conversation_id conversation-id :reason reason}))
    (catch :default err
      (js/console.warn "[knoxx] abort failed" (.-message err))
      {:ok false :error (.-message err)})))

(defn open-stream!
  "Opens /ws/stream scoped to conversation-id. Calls (on-envelope clj-map) per message,
  (on-close) once. Resolves to the socket once open; rejects if it cannot connect."
  [config conversation-id {:keys [on-envelope on-close]}]
  (js/Promise.
   (fn [resolve-fn reject-fn]
     (let [^js ws (js/WebSocket. (ws-url config conversation-id))
           opened? (atom false)]
       (.addEventListener ws "open" (fn [] (reset! opened? true) (resolve-fn ws)))
       (.addEventListener ws "message"
                          (fn [^js event]
                            (when-let [parsed (u/parse-json (str (.-data event)) nil)]
                              (on-envelope (js->clj parsed :keywordize-keys true)))))
       (.addEventListener ws "error"
                          (fn [_]
                            (when-not @opened?
                              (reject-fn (js/Error. "Knoxx stream connection failed")))))
       (.addEventListener ws "close" (fn [] (when on-close (on-close))))))))

(defn close-stream!
  "Closes a stream socket if it is still open."
  [^js ws]
  (when (and ws (< (.-readyState ws) 2))
    (.close ws)))
