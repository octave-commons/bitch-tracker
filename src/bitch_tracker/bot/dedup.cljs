(ns bitch-tracker.bot.dedup
  (:require [bitch-tracker.shared.util :as u]))

(defn make-state
  "Returns a fresh deduplication state object."
  []
  #js {:cache (js/Map.)
       :hits 0
       :misses 0})

(defn- now-ms []
  (.getTime (js/Date.)))

(defn- prune-by-age! [^js state max-age-ms]
  (let [cache (.-cache state)
        cutoff (- (now-ms) max-age-ms)]
    (doseq [entry (array-seq (js/Array.from cache))]
      (when (< (aget entry 1) cutoff)
        (.delete cache (aget entry 0))))))

(defn- prune-by-size! [^js state max-size]
  (let [cache (.-cache state)
        size (.-size cache)]
    (when (> size max-size)
      (let [entries (js/Array.from cache)]
        (.sort entries (fn [a b] (- (aget a 1) (aget b 1))))
        (doseq [i (range (- size max-size))]
          (.delete cache (aget (aget entries i) 0)))
        true))))

(defn seen?
  "Returns true if id is present in the dedup cache."
  [^js state id]
  (.has (.-cache state) id))

(defn add!
  "Adds id to the cache, pruning old and excess entries. Returns true for a new id, false for a cache hit."
  [^js state id max-age-ms max-size]
  (prune-by-age! state max-age-ms)
  (let [cache (.-cache state)]
    (if (.has cache id)
      (do (set! (.-hits state) (inc (.-hits state)))
          false)
      (do (.set cache id (now-ms))
          (set! (.-misses state) (inc (.-misses state)))
          (prune-by-size! state max-size)
          true))))

(defn stats
  "Returns an object with the cache size, hits and misses."
  [^js state]
  #js {:size (.-size (.-cache state))
       :hits (.-hits state)
       :misses (.-misses state)})

(defn persist-path
  "Returns the default filesystem path for the dedup cache file."
  []
  (let [path (js/require "path")]
    (.join path ((.-cwd js/process)) "dedup-cache.json")))

(defn persist!
  "Writes the current cache entries to path as JSON."
  [^js state path]
  (try
    (let [fs (js/require "fs")
          entries (js/Array.from (.-cache state))
          data (clj->js (map (fn [e] [(aget e 0) (aget e 1)]) entries))]
      (.writeFileSync fs path (js/JSON.stringify data)))
    (catch :default err
      (js/console.warn "[dedup] persist failed" (.-message err)))))

(defn load!
  "Loads cache entries from path into state if the file exists."
  [^js state path]
  (try
    (let [fs (js/require "fs")]
      (when (.existsSync fs path)
        (let [raw (.readFileSync fs path "utf8")
              data (u/parse-json raw #js [])]
          (doseq [entry (array-seq data)]
            (let [id (aget entry 0)
                  ts (aget entry 1)]
              (when (and id (number? ts))
                (.set (.-cache state) id ts)))))))
    (catch :default err
      (js/console.warn "[dedup] load failed" (.-message err)))))
