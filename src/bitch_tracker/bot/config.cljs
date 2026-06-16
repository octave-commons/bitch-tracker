(ns bitch-tracker.bot.config
  "Reads and validates bot configuration from environment variables and JSON files.
  All I/O; no domain policy computed here."
  (:require [bitch-tracker.domain.policy :as defaults]
            [bitch-tracker.domain.watchlist :as watchlist]
            ["fs" :as fs]
            ["path" :as path]))

(defn- env
  "Returns the value of environment variable k, or fallback."
  [k fallback]
  (or (aget js/process.env k) fallback))

(defn- parse-int-env
  "Parses environment variable k as an integer, returning fallback when absent or invalid."
  [k fallback]
  (let [v (aget js/process.env k)]
    (if v
      (let [n (js/parseInt v 10)]
        (if (js/isNaN n) fallback n))
      fallback)))

(defn- load-json-file
  "Reads and parses a JSON file at file-path, returning nil when the file is absent."
  [file-path]
  (when (fs/existsSync file-path)
    (js/JSON.parse (fs/readFileSync file-path "utf8"))))

(defn- parse-watch-entries
  "Reads the watch-entries JSON file and compiles each entry into a domain WatchEntry."
  [file-path]
  (when-let [raw (load-json-file file-path)]
    (->> (js/Array.from raw)
         (mapv (fn [e]
                 (watchlist/compile-entry
                  {:label   (str (aget e "label"))
                   :pattern (str (aget e "pattern"))}))))))

(defn load!
  "Reads all config sources and returns a validated BotConfig map.
  Throws when a required variable is absent."
  []
  (let [token         (env "DISCORD_TOKEN" nil)
        app-id        (env "DISCORD_APP_ID" nil)
        bot-user-id   (env "BOT_USER_ID" nil)
        socket-port   (parse-int-env "SOCKET_PORT" defaults/default-socket-port)
        tracker-ch    (env "TRACKER_CHANNEL_ID" defaults/tracker-channel-id)
        watch-ch      (env "WATCH_CHANNEL_ID" defaults/watch-channel-id)
        threshold     (parse-int-env "LABEL_THRESHOLD" defaults/label-threshold)
        flush-ms      (parse-int-env "FLUSH_EVERY_MS" defaults/default-flush-every-ms)
        batch-size    (parse-int-env "MAX_BATCH_SIZE" defaults/default-max-batch-size)
        max-persisted (parse-int-env "MAX_PERSISTED_EVENTS" defaults/default-max-persisted)
        watch-file    (env "WATCH_ENTRIES_FILE"
                           (.join path (.cwd js/process) "watch-entries.json"))
        watch-entries (parse-watch-entries watch-file)]
    (when-not token   (throw (js/Error. "DISCORD_TOKEN is required")))
    (when-not app-id  (throw (js/Error. "DISCORD_APP_ID is required")))
    {:token              token
     :app-id             app-id
     :bot-user-id        (or bot-user-id "")
     :socket-port        socket-port
     :tracker-channel-id tracker-ch
     :watch-channel-id   watch-ch
     :label-threshold    threshold
     :flush-every-ms     flush-ms
     :max-batch-size     batch-size
     :max-persisted      max-persisted
     :watch-entries      (or watch-entries [])}))
