(ns bitch-tracker.bot.config
  (:require [clojure.string :as str]
            [bitch-tracker.shared.util :as u]
            [bitch-tracker.shared.constants :as c]))

(defn- read-file-sync [path]
  (try
    (let [fs (js/require "fs")]
      (when (.existsSync fs path)
        (.readFileSync fs path "utf8")))
    (catch :default _ nil)))

(defn- load-json [path fallback]
  (let [raw (read-file-sync path)]
    (or (u/parse-json raw nil) fallback)))

(defn- env-or [key default]
  (or (when-let [v (aget js/process.env key)]
        (when-not (str/blank? v) v))
      default))

(defn load-bot-config []
  (let [path (js/require "path")
        bot-json-path (.join path ((.-cwd js/process)) "bot.json")
        bot-json (load-json bot-json-path #js {})
        token (env-or "BITCH_TRACKER_BOT_TOKEN" (u/jget bot-json "token"))
        app-id (env-or "BITCH_TRACKER_APP_ID" (u/jget bot-json "appId"))
        bot-user-id (env-or "BITCH_TRACKER_BOT_USER_ID" (u/jget bot-json "botUserId"))]
    (when (or (str/blank? token) (= token "<bot-token>"))
      (throw (js/Error. "Bot token not configured. Set BITCH_TRACKER_BOT_TOKEN env var or add token to bot.json")))
    {:token token
     :app-id (str (or app-id ""))
     :bot-user-id (str (or bot-user-id ""))
     :bot-username (or (u/jget bot-json "username") "BitchTrackerBot")
     :plugin-status-channel-id (env-or "BITCH_TRACKER_PLUGIN_STATUS_CHANNEL_ID" (u/jget bot-json "plugin-status-channel"))
     :slapper-of-bitches-role-id (env-or "BITCH_TRACKER_SLAPPER_ROLE_ID" (u/jget bot-json "slapper-of-bitches-role"))
     :socket-port (js/parseInt (env-or "BITCH_TRACKER_SOCKET_PORT" "7878") 10)
     :public-base-url (env-or "BITCH_TRACKER_PUBLIC_BASE_URL" "")
     :openplanner-base-url (str/replace (env-or "OPENPLANNER_BASE_URL" c/default-openplanner-url) #"/+$" "")
     :openplanner-api-key (env-or "OPENPLANNER_API_KEY" "")
     :openplanner-project (env-or "OPENPLANNER_PROJECT" c/default-project)
     :tracker-channel-id (env-or "BITCH_TRACKER_CHANNEL_ID" c/tracker-channel-id)
     :watch-channel-id (env-or "BITCH_TRACKER_WATCH_CHANNEL_ID" c/watch-channel-id)
     :label-threshold (js/parseInt (env-or "BITCH_TRACKER_LABEL_THRESHOLD" (str c/label-threshold)) 10)
     :flush-every-ms (js/parseInt (env-or "BITCH_TRACKER_FLUSH_MS" (str c/flush-every-ms)) 10)
     :semantic-scan-every-ms (js/parseInt (env-or "BITCH_TRACKER_SEMANTIC_SCAN_MS" (str c/semantic-scan-every-ms)) 10)
     :max-batch-size (js/parseInt (env-or "BITCH_TRACKER_MAX_BATCH" (str c/max-batch-size)) 10)
     :max-persisted-events (js/parseInt (env-or "BITCH_TRACKER_MAX_PERSISTED" (str c/max-persisted-events)) 10)
     :backfill-days (js/parseInt (env-or "BITCH_TRACKER_BACKFILL_DAYS" (str c/backfill-days)) 10)}))

(defn load-watchlist []
  (let [path (js/require "path")
        watchlist-path (.join path ((.-cwd js/process)) "OpenPlannerModerationWatchlist.json")]
    (load-json watchlist-path #js {:watchTerms #js [] :watchRegexes #js [] :patterns #js []})))

(defn- compile-regex [term]
  (let [match (.match (str term) (js/RegExp. "^/(.*)/([a-z]*)$" "i"))]
    (if match
      (try
        (let [body (aget match 1)
              flags (aget match 2)
              final-flags (if (str/includes? flags "i") flags (str flags "i"))]
          (js/RegExp. body final-flags))
        (catch :default _ nil))
      (js/RegExp. (str "\\b" (u/escape-regexp term) "\\b") "i"))))

(defn watch-pattern [term]
  (when term
    (when-let [pattern (compile-regex term)]
      {:label term :pattern pattern})))

(defn- pattern-object? [entry]
  (and (object? entry)
       (not (nil? (u/jget entry "pattern")))))

(defn- normalize-watch-entry [entry]
  (cond
    (string? entry)
    (watch-pattern entry)

    (pattern-object? entry)
    (let [name (u/jget entry "name")
          pattern-str (u/jget entry "pattern")
          label (if (str/blank? name) pattern-str (str/trim (str name)))]
      (when-let [pattern (compile-regex pattern-str)]
        {:label label :pattern pattern}))

    :else nil))

(defn watch-entries [watchlist]
  (let [cfg-terms (u/js-values (u/jget watchlist "watchTerms"))
        cfg-regexes (u/js-values (u/jget watchlist "watchRegexes"))
        cfg-patterns (u/js-values (u/jget watchlist "patterns"))]
    (->> (concat cfg-terms cfg-regexes cfg-patterns)
         (map normalize-watch-entry)
         (remove nil?)
         (reduce (fn [acc entry]
                   (if (some #(= (:label entry) (:label %)) acc)
                     acc
                     (conj acc entry)))
                 [])
         vec)))

(defn watch-terms [watchlist]
  (->> (watch-entries watchlist)
       (map :label)
       vec))
