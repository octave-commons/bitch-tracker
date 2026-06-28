(ns bitch-tracker.bot.dedup
  "In-memory deduplication with short TTL.")

(defn make-state
  "Returns a fresh deduplication state object."
  []
  #js {:cache (js/Map.)
       :hits 0
       :misses 0})

(defn- now-ms []
  (.getTime (js/Date.)))

(defn- prune! [^js state ttl-ms]
  (let [cache (.-cache state)
        cutoff (- (now-ms) ttl-ms)]
    (doseq [entry (array-seq (js/Array.from cache))]
      (when (< (aget entry 1) cutoff)
        (.delete cache (aget entry 0))))))

(defn seen?
  "Returns true if id is present in the dedup cache."
  [^js state id]
  (prune! state 300000)
  (.has (.-cache state) id))

(defn add!
  "Adds id to the cache with the given TTL. Returns true for a new id, false for a cache hit."
  [^js state id ttl-ms _max-size]
  (prune! state (or ttl-ms 300000))
  (let [cache (.-cache state)]
    (if (.has cache id)
      (do (set! (.-hits state) (inc (.-hits state)))
          false)
      (do (.set cache id (now-ms))
          (set! (.-misses state) (inc (.-misses state)))
          true))))

(defn stats
  "Returns an object with the cache size, hits and misses."
  [^js state]
  #js {:size (.-size (.-cache state))
       :hits (.-hits state)
       :misses (.-misses state)})

(defn persist-path
  "Deprecated: no-op path for compatibility."
  []
  "")

(defn persist!
  "No-op: dedup cache is in-memory only."
  [_state _path]
  nil)

(defn load!
  "No-op: dedup cache is in-memory only."
  [_state _path]
  nil)
