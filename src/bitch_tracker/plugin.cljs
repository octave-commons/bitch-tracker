(ns bitch-tracker.plugin
  (:require [clojure.string :as str]))

(def plugin-name "BitchTracker")
(def plugin-version "0.0.1")
(def plugin-description
  "Shadow-CLJS BetterDiscord plugin scaffold for Discord moderation/event tracking.")

(defn- global-get [k]
  (aget js/globalThis k))

(defn- meta-field [meta k fallback]
  (let [value (aget meta k)]
    (if (and (string? value) (not (str/blank? value)))
      value
      fallback)))

(defn- bd-api []
  (global-get "BdApi"))

(defn- log! [level meta & xs]
  (let [name (meta-field meta "name" plugin-name)
        logger (some-> (bd-api) (aget "Logger"))
        logger-fn (some-> logger (aget level))
        console-fn (or (aget js/console level) (.-log js/console))]
    (if logger-fn
      (.apply logger-fn logger (into-array (cons name xs)))
      (.apply console-fn js/console (into-array (cons (str "[" name "]") xs))))))

(defn- toast! [message type]
  (let [ui (some-> (bd-api) (aget "UI"))
        show-toast (some-> ui (aget "showToast"))]
    (when show-toast
      (.call show-toast ui message #js {:type type}))))

(defn ^:async sleep-ms [ms]
  (await
   (js/Promise.
    (fn [resolve _reject]
      (js/setTimeout resolve ms)))))

(defn- make-state []
  #js {:started false
       :startedAt nil
       :stoppedAt nil})

(defn ^:async start-plugin! [meta state]
  (await (sleep-ms 0))
  (aset state "started" true)
  (aset state "startedAt" (.toISOString (js/Date.)))
  (aset state "stoppedAt" nil)
  (log! "info" meta "started" (str "v" (meta-field meta "version" plugin-version)))
  (toast! (str (meta-field meta "name" plugin-name) " started") "success")
  state)

(defn stop-plugin! [meta state]
  (aset state "started" false)
  (aset state "stoppedAt" (.toISOString (js/Date.)))
  (log! "info" meta "stopped")
  (toast! (str (meta-field meta "name" plugin-name) " stopped") "info")
  state)

(defn- append-text! [document root tag text]
  (let [node (.createElement document tag)]
    (set! (.-textContent node) text)
    (.appendChild root node)
    node))

(defn settings-panel [meta state]
  (when-let [document (global-get "document")]
    (let [root (.createElement document "div")
          title (.createElement document "h2")]
      (set! (.-cssText (.-style root))
            "padding:16px;display:flex;flex-direction:column;gap:8px;color:var(--text-normal);")
      (set! (.-textContent title) (meta-field meta "name" plugin-name))
      (.appendChild root title)
      (append-text! document root "p" (meta-field meta "description" plugin-description))
      (append-text! document root "p" (str "Version: " (meta-field meta "version" plugin-version)))
      (append-text! document root "p" (str "Started: " (boolean (aget state "started"))))
      root)))

(defn plugin-factory [meta]
  (let [state (make-state)]
    #js {:start (fn []
                  (start-plugin! meta state))
         :stop (fn []
                 (stop-plugin! meta state))
         :getSettingsPanel (fn []
                             (settings-panel meta state))}))

(defn main! []
  ;; BetterDiscord's current plugin loader accepts this factory shape:
  ;; module.exports = meta => ({start, stop, ...})
  (set! (.-exports js/module) plugin-factory))
