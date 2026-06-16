(ns bitch-tracker.domain.dedup
  "Pure event-deduplication store.

  State shape:
    {:cache {id -> {:at ms :ttl-ms ms}}
     :default-ttl-ms 86400000}

  Callers pass the current time explicitly; this namespace has no I/O and does
  not access js/Date.")

(defn empty-store
  "Returns an empty dedup store with a 24-hour default TTL."
  []
  {:cache {}
   :default-ttl-ms 86400000})

(defn seen?
  "True when id is present and has not yet expired relative to ttl-ms and
  now-ms."
  [store id ttl-ms now-ms]
  (when-let [entry (get-in store [:cache id])]
    (< (- now-ms (:at entry)) ttl-ms)))

(defn add
  "Records id as seen at now-ms using the store's default TTL."
  [store id now-ms]
  (assoc-in store [:cache id] {:at now-ms :ttl-ms (:default-ttl-ms store)}))

(defn evict-expired
  "Returns a new store with entries whose TTL has passed removed."
  [store now-ms]
  (update store :cache
          (fn [cache]
            (->> cache
                 (remove (fn [[_id entry]]
                           (>= (- now-ms (:at entry)) (:ttl-ms entry))))
                 (into {})))))
