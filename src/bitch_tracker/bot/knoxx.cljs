(ns bitch-tracker.bot.knoxx
  "Knoxx HTTP + WebSocket transport for KnoxxLens turns and session recall.
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

(defn ^:async get-json!
  "GETs path on Knoxx. Returns {:ok bool :status n :body clj-map}."
  [config path]
  (let [res (await (js/fetch (str (:knoxx-base-url config) path)
                             #js {:method "GET"
                                  :headers (headers config)}))
        text (await (.text res))
        parsed (u/parse-json text nil)]
    {:ok (.-ok res)
     :status (.-status res)
     :body (if parsed (js->clj parsed :keywordize-keys true) {:raw text})}))

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

(defn ^:async list-sessions!
  "Lists past Knoxx sessions via GET /api/memory/sessions."
  [config {:keys [limit offset]}]
  (let [lim (max 1 (min 100 (or limit 30)))
        off (max 0 (or offset 0))]
    (await (get-json! config (str "/api/memory/sessions?limit=" lim "&offset=" off)))))

(defn ^:async get-session!
  "Fetches rows for a past Knoxx session via GET /api/memory/sessions/:sessionId."
  [config session-id]
  (await (get-json! config (str "/api/memory/sessions/" (js/encodeURIComponent (str session-id))))))

(def ^:private max-image-bytes
  "Maximum byte size for a single fetched Discord image attachment (12 MiB)."
  (* 12 1024 1024))

(defn- ^:async materialize-image-part!
  "Fetches a remote image part `{:type \"image\" :url ...}` into a base64 `data:` URL
  with a 4.5s timeout. Returns nil if the URL is expired/unreachable or exceeds max-image-bytes
  so an expired Discord CDN signature never aborts the whole Knoxx turn."
  [part]
  (cond
    (not= "image" (str (:type part)))
    part

    (and (string? (:data part)) (.startsWith (str (:data part)) "data:"))
    part

    (string? (:url part))
    (try
      (let [signal (when (exists? js/AbortSignal.timeout)
                     (js/AbortSignal.timeout 4500))
            opts (if signal #js {:method "GET" :signal signal} #js {:method "GET"})
            res (await (js/fetch (:url part) opts))]
        (if-not (.-ok res)
          (do (js/console.warn "[knoxx-lens] skipping unreachable image attachment" (:url part) (.-status res))
              nil)
          (let [ab (await (.arrayBuffer res))
                buf (js/Buffer.from ab)
                size (.-length buf)]
            (if (or (zero? size) (> size max-image-bytes))
              (do (js/console.warn "[knoxx-lens] skipping oversized/empty image attachment" (:url part) size)
                  nil)
              (let [header-mime (some-> res .-headers (.get "content-type") (.split ";") first .trim)
                    mime (if (and header-mime (.startsWith header-mime "image/"))
                           header-mime
                           (or (:mimeType part) "image/png"))
                    data-url (str "data:" mime ";base64," (.toString buf "base64"))]
                (-> part
                    (dissoc :url)
                    (assoc :data data-url :mimeType mime)))))))
      (catch :default err
        (js/console.warn "[knoxx-lens] skipping failed image attachment" (:url part) (.-message err))
        nil))

    :else nil))

(defn- ^:async materialize-content-parts!
  "Materializes any `:contentParts` on a turn body in parallel, dropping failed fetches."
  [body]
  (let [parts (vec (or (:contentParts body) []))]
    (if (empty? parts)
      body
      (let [resolved (await (js/Promise.all (clj->js (mapv materialize-image-part! parts))))]
        (assoc body :contentParts (vec (remove nil? (array-seq resolved))))))))

(defn ^:async start-direct-turn!
  "Queues a direct (non-RAG) Knoxx turn after materializing any multimodal image parts.
  Resolves to post-json! result."
  [config body]
  (let [ready-body (await (materialize-content-parts! body))]
    (await (post-json! config "/api/knoxx/direct/start" ready-body))))

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
