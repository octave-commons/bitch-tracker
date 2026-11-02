(ns bitch-tracker.bot.desktop
  "Desktop integration for KnoxxLens: indexes i3 windows from desktop-indexer state,
   enables one-click i3 window focusing, and proxies NPU OCR requests."
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [cljs.reader :as reader]
            [clojure.string :as str]))

(def ^:private state-edn-path
  (path/join (os/homedir) ".cache/desktop-index/state.edn"))

(def ^:private ocr-url "http://127.0.0.1:8099")

(defn list-windows
  "Reads active desktop windows from ~/.cache/desktop-index/state.edn."
  []
  (try
    (if (.existsSync fs state-edn-path)
      (let [content (.readFileSync fs state-edn-path "utf8")
            parsed (reader/read-string content)
            windows-map (:windows parsed {})]
        (vec
         (for [[wid-str win] windows-map]
           {:id (or (:node-id win) (str "window:" (:class win) ":" wid-str))
            :wid (:wid win)
            :conId (:con-id win)
            :class (or (:class win) "unknown")
            :title (or (:title win) "Untitled Window")
            :workspace (or (:ws win) "1")
            :ocrText (or (:ocr-text win) "")
            :topics (vec (or (:topics win) []))
            :categories (vec (or (:categories win) []))
            :search (or (:search-text win) "")})))
      [])
    (catch :default err
      (js/console.warn "[desktop] Failed to read state.edn:" (ex-message err))
      [])))

(defn focus-window!
  "Focuses an i3 window by strictly validated numeric X11 window ID (wid) or container ID (conId)."
  [wid-or-con-id]
  (js/Promise.
   (fn [res _reject]
     (let [safe-id (cond
                     (number? wid-or-con-id)
                     (when (and (int? wid-or-con-id) (pos? wid-or-con-id))
                       (str (int wid-or-con-id)))

                     (string? wid-or-con-id)
                     (let [trimmed (str/trim wid-or-con-id)]
                       (when (re-matches #"^[0-9]{1,16}$" trimmed)
                         trimmed))

                     :else nil)]
       (if-not safe-id
         (res {:ok false :error "Invalid window or container ID: must be a positive integer"})
         (let [cmd (str "[id=\"" safe-id "\"] focus")]
           (cp/execFile "i3-msg" #js [cmd]
                        (fn [err stdout stderr]
                          (if err
                            (res {:ok false :error (or (when stderr (str stderr)) (ex-message err))})
                            (res {:ok true :output (str stdout)}))))))))))

(defn ^:async ocr-image!
  "Submits a base64-encoded image to the local Intel NPU OCR service."
  [b64-image]
  (try
    (let [resp (await (js/fetch (str ocr-url "/ocr")
                                #js {:method "POST"
                                     :headers #js {"Content-Type" "application/json"}
                                     :body (js/JSON.stringify #js {:image b64-image})
                                     :signal (.timeout js/AbortSignal 10000)}))]
      (if (.-ok resp)
        (let [json (await (.json resp))]
          (js->clj json :keywordize-keys true))
        {:error (str "OCR HTTP " (.-status resp))}))
    (catch :default err
      {:error (ex-message err)})))

(defn register-handlers!
  "Wires desktop socket events onto a newly connected BetterDiscord plugin socket."
  [^js socket]
  (.on socket "lens:desktop:list"
       (fn [data-or-ack ack-fn]
         (let [ack (if (fn? data-or-ack) data-or-ack ack-fn)
               wins (list-windows)
               res {:ok true :windows wins}]
           (.emit socket "bot:lens:desktop:list" (clj->js res))
           (when (fn? ack) (ack (clj->js res))))))

  (.on socket "lens:desktop:focus"
       (fn [data ack]
         (let [wid (or (when (object? data) (aget data "wid"))
                       (when (object? data) (aget data "id"))
                       data)]
           (-> (focus-window! wid)
               (.then (fn [res]
                        (.emit socket "bot:lens:desktop:focus" (clj->js res))
                        (when (fn? ack) (ack (clj->js res)))))))))

  (.on socket "lens:desktop:ocr"
       (fn [data ack]
         (let [b64 (when (object? data) (aget data "image"))]
           (-> (ocr-image! b64)
               (.then (fn [res]
                        (.emit socket "bot:lens:desktop:ocr" (clj->js res))
                        (when (fn? ack) (ack (clj->js res))))))))))

