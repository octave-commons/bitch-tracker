(ns bitch-tracker.bot.config
  "Reads and validates bot configuration from environment variables and JSON files.
  All I/O; no domain policy computed here."
  (:require ["fs" :as fs]
            ["path" :as path]
            [bitch-tracker.domain.policy :as policy]
            [bitch-tracker.domain.watchlist :as watchlist]
            [bitch-tracker.shared.constants :as c]
            [bitch-tracker.shared.util :as u]
            [clojure.string :as str]))

(defn- env-or
  "Returns the value of environment variable k when present and non-blank, else fallback."
  [k fallback]
  (let [v (aget js/process.env k)]
    (if (str/blank? v) fallback v)))

(defn- parse-int-env
  "Parses environment variable k as an integer, returning fallback when absent or invalid."
  [k fallback]
  (let [v (aget js/process.env k)]
    (if (str/blank? v)
      fallback
      (let [n (js/parseInt v 10)]
        (if (js/isNaN n) fallback n)))))

(defn- load-json-file
  "Reads and parses a JSON file at file-path, returning fallback when the file is absent or unreadable."
  [file-path fallback]
  (try
    (if (fs/existsSync file-path)
      (let [raw (fs/readFileSync file-path "utf8")]
        (if (seq raw) (js/JSON.parse raw) fallback))
      fallback)
    (catch :default _ fallback)))

(defn- parse-id-set
  "Coerces a comma-separated string, sequence, or set into a set of trimmed non-blank strings."
  [value]
  (cond
    (set? value)
    (into #{} (comp (map str) (map str/trim) (remove str/blank?)) value)

    (string? value)
    (into #{} (comp (map str/trim) (remove str/blank?)) (str/split value #","))

    (or (sequential? value) (array? value))
    (into #{} (comp (map str) (map str/trim) (remove str/blank?)) value)

    :else #{}))

(defn- legacy-regex-entry
  "Converts a legacy watch term or regex string into a {:label :pattern-string} entry."
  [term]
  (let [m (.match (str term) (js/RegExp. "^/(.*)/([a-z]*)$"))]
    (if m
      {:label   (str term)
       :pattern (aget m 1)}
      {:label   (str term)
       :pattern (str "\\b" (u/escape-regexp term) "\\b")})))

(defn- legacy-pattern->entry
  "Converts a legacy pattern object into a {:label :pattern-string} entry."
  [entry]
  (let [entry-name (aget entry "name")
        pattern (aget entry "pattern")]
    (when (some? pattern)
      (let [{:keys [label pattern]} (legacy-regex-entry pattern)]
        {:label   (if (str/blank? entry-name) label (str/trim (str entry-name)))
         :pattern pattern}))))

(defn- legacy-entries
  "Extracts {:label :pattern-string} entries from the legacy OpenPlannerModerationWatchlist shape."
  [watchlist]
  (let [terms (js/Array.from (or (aget watchlist "watchTerms") #js []))
        regexes (js/Array.from (or (aget watchlist "watchRegexes") #js []))
        patterns (js/Array.from (or (aget watchlist "patterns") #js []))]
    (concat
     (map legacy-regex-entry terms)
     (map legacy-regex-entry regexes)
     (keep legacy-pattern->entry patterns))))

(defn- new-entries
  "Extracts {:label :pattern-string} entries from the new watch-entries.json shape."
  [entries]
  (mapv (fn [e]
          {:label   (str (aget e "label"))
           :pattern (str (aget e "pattern"))})
        (js/Array.from entries)))

(defn- compile-entry-safe
  "Compiles a watch entry through the domain watchlist layer, skipping invalid patterns."
  [entry]
  (try
    (watchlist/compile-entry entry)
    (catch :default _ nil)))

(defn watch-pattern
  "Compiles a single legacy watch term or regex string into a labeled RegExp pattern."
  [term]
  (when term
    (compile-entry-safe (legacy-regex-entry term))))

(defn load-watchlist
  "Reads the preferred watch-entries.json, falling back to the legacy OpenPlannerModerationWatchlist.json."
  []
  (let [cwd (.cwd js/process)
        new-path (.join path cwd "watch-entries.json")
        legacy-path (.join path cwd "OpenPlannerModerationWatchlist.json")]
    (if (fs/existsSync new-path)
      (load-json-file new-path #js [])
      (load-json-file legacy-path #js {:watchTerms #js [] :watchRegexes #js [] :patterns #js []}))))

(defn watch-entries
  "Normalizes a raw watchlist (new array or legacy object) into compiled domain watch entries."
  [watchlist]
  (let [entries (if (array? watchlist)
                  (new-entries watchlist)
                  (legacy-entries watchlist))]
    (->> entries
         (map compile-entry-safe)
         (remove nil?)
         (reduce (fn [acc entry]
                   (if (some #(= (:label entry) (:label %)) acc)
                     acc
                     (conj acc entry)))
                 [])
         vec)))

(defn load-bot-config
  "Reads all config sources and returns a validated BotConfig map.
  Throws when DISCORD_TOKEN or DISCORD_APP_ID is missing."
  []
  (let [bot-json-path (.join path (.cwd js/process) "bot.json")
        bot-json (load-json-file bot-json-path #js {})
        token (env-or "DISCORD_TOKEN" (aget bot-json "token"))
        app-id (env-or "DISCORD_APP_ID" (aget bot-json "appId"))]
    (when (str/blank? token)
      (throw (js/Error. "DISCORD_TOKEN is required")))
    (when (str/blank? app-id)
      (throw (js/Error. "DISCORD_APP_ID is required")))
    {:token                      token
     :app-id                     app-id
     :bot-user-id                (env-or "BOT_USER_ID" (str (or (aget bot-json "botUserId") "")))
     :bot-username               (or (aget bot-json "username") "BitchTrackerBot")
     :socket-port                (parse-int-env "SOCKET_PORT" policy/default-socket-port)
     :tracker-channel-id         (env-or "TRACKER_CHANNEL_ID" policy/tracker-channel-id)
     :watch-channel-id           (env-or "WATCH_CHANNEL_ID" policy/watch-channel-id)
     :label-threshold            (parse-int-env "LABEL_THRESHOLD" policy/label-threshold)
     :flush-every-ms             (parse-int-env "FLUSH_EVERY_MS" policy/default-flush-every-ms)
     :max-batch-size             (parse-int-env "MAX_BATCH_SIZE" policy/default-max-batch-size)
     :max-persisted-events       (parse-int-env "MAX_PERSISTED_EVENTS" policy/default-max-persisted)
     :openplanner-base-url       (str/replace (env-or "OPENPLANNER_BASE_URL" c/default-openplanner-url) #"/+$" "")
     :openplanner-api-key        (env-or "OPENPLANNER_API_KEY" (or (aget bot-json "openplannerApiKey") ""))
     :openplanner-project        (env-or "OPENPLANNER_PROJECT" c/default-project)
     :public-base-url            (env-or "BITCH_TRACKER_PUBLIC_BASE_URL" (or (aget bot-json "publicBaseUrl") ""))
     :plugin-status-channel-id   (env-or "BITCH_TRACKER_PLUGIN_STATUS_CHANNEL_ID" (or (aget bot-json "pluginStatusChannelId") ""))
     :slapper-of-bitches-role-id (env-or "BITCH_TRACKER_SLAPPER_OF_BITCHES_ROLE_ID" (or (aget bot-json "slapperOfBitchesRoleId") ""))
     :guild-ids                  (parse-id-set (env-or "BITCH_TRACKER_GUILD_IDS" (aget bot-json "guildIds")))
     :known-label-user-ids       (parse-id-set (env-or "BITCH_TRACKER_KNOWN_LABEL_USER_IDS" (aget bot-json "knownLabelUserIds")))
     :backfill-days              (parse-int-env "BITCH_TRACKER_BACKFILL_DAYS" c/backfill-days)
     :semantic-scan-every-ms     c/semantic-scan-every-ms}))
