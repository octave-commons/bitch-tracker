(ns bitch-tracker.domain.dedup
  "Pure deduplication logic over a TTL cache map.
  I/O (persistence to disk) lives in bot.dedup-store.")

(defn empty-cache
  "Returns an empty deduplication cache."
  []
  {})

(defn seen?
  "Returns true when id exists in the cache and has not expired."
  [cache id now-ms ttl-ms]
  (when-let [ts (get cache id)]
    (< (- now-ms ts) ttl-ms)))

(defn add
  "Returns a new cache with id recorded at timestamp now-ms."
  [cache id now-ms]
  (assoc cache id now-ms))

(defn evict-expired
  "Returns a new cache with all entries older than ttl-ms removed."
  [cache now-ms ttl-ms]
  (into {} (filter (fn [[_id ts]] (< (- now-ms ts) ttl-ms)) cache)))
