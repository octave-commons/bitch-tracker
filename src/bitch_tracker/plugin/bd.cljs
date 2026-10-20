(ns bitch-tracker.plugin.bd
  "BetterDiscord API interop shared by the BitchTracker and KnoxxLens plugins.
  All I/O against globalThis.BdApi; no domain policy."
  (:require [bitch-tracker.shared.util :as u]))

(defn global-get
  "Reads a property from globalThis."
  [k]
  (aget js/globalThis k))

(defn bd-api
  "Returns globalThis.BdApi, or nil outside BetterDiscord."
  []
  (global-get "BdApi"))

(defn webpack
  "Returns BdApi.Webpack."
  []
  (u/jget (bd-api) "Webpack"))

(defn get-store
  "Looks up a Discord Flux store by name through BdApi.Webpack.getStore."
  [store-name]
  (let [wp (webpack)]
    (when-let [f (u/jget wp "getStore")]
      (.call f wp store-name))))

(defn get-module
  "Finds a Discord webpack module matching pred, searching exports."
  [pred]
  (let [wp (webpack)]
    (when-let [f (u/jget wp "getModule")]
      (.call f wp pred #js {:searchExports true}))))

(defn meta-field
  "Reads a field from BetterDiscord plugin metadata, falling back when blank."
  [plugin-meta k fallback]
  (let [value (when plugin-meta (aget plugin-meta k))]
    (if (u/present-string? value) value fallback)))

(defn log!
  "Logs through BdApi.Logger when present, else console, prefixed with the plugin name."
  [level plugin-name & xs]
  (let [logger (u/jget (bd-api) "Logger")
        logger-fn (u/jget logger level)
        console-fn (or (u/jget js/console level) (.-log js/console))]
    (if logger-fn
      (.apply logger-fn logger (into-array (cons plugin-name xs)))
      (.apply console-fn js/console (into-array (cons (str "[" plugin-name "]") xs))))))

(defn toast!
  "Shows a BetterDiscord toast when the UI API is available."
  [message toast-type]
  (when-let [show-toast (u/jget (bd-api) "UI" "showToast")]
    (.call show-toast (u/jget (bd-api) "UI") message #js {:type toast-type})))

(defn data-load
  "Loads a persisted BetterDiscord plugin value."
  [plugin setting-key]
  (u/jcall (u/jget (bd-api) "Data") "load" plugin setting-key))

(defn data-save!
  "Persists a BetterDiscord plugin value."
  [plugin setting-key value]
  (u/jcall (u/jget (bd-api) "Data") "save" plugin setting-key value))

(defn setting
  "Loads a persisted value, returning fallback when it was never saved."
  [plugin setting-key fallback]
  (let [value (data-load plugin setting-key)]
    (if (undefined? value) fallback value)))

(defn ^:async sleep-ms
  "Returns a promise that resolves after ms milliseconds."
  [ms]
  (await
   (js/Promise.
    (fn [resolve-fn _reject]
      (js/setTimeout resolve-fn ms)))))
