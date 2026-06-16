(ns bitch-tracker.bot.dedup-store
  "Persists and loads the dedup cache from filesystem.
  All I/O; delegates logic to domain.dedup."
  (:require [bitch-tracker.domain.dedup :as dedup]
            ["fs" :as fs]))

(defn load!
  "Reads the cache from cache-path, returning an empty cache on any error."
  [cache-path]
  (try
    (let [raw (fs/readFileSync cache-path "utf8")]
      (js->clj (js/JSON.parse raw) :keywordize-keys false))
    (catch :default _ (dedup/empty-cache))))

(defn save!
  "Writes cache to cache-path as JSON, evicting expired entries first."
  [cache-path cache now-ms ttl-ms]
  (let [pruned (dedup/evict-expired cache now-ms ttl-ms)]
    (fs/writeFileSync cache-path (js/JSON.stringify (clj->js pruned)) "utf8")
    pruned))
