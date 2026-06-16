(ns bitch-tracker.domain.watchlist
  "Pure moderation-watchlist logic.

  Compiles raw watchlist entries into labeled regex entries and tests messages
  against them. No I/O."
  (:require [clojure.string :as str]))

(defn- escape-regexp
  "Escapes regex metacharacters in value."
  [value]
  (.replace (str value) (js/RegExp. "[.*+?^${}()|[\\]\\\\]" "g") "\\$&"))

(defn- compile-regex
  "Compiles a watch term into a case-insensitive RegExp. Slash-delimited
  strings are parsed as regex literals; plain terms become word-boundary
  patterns."
  [term]
  (let [match (.match (str term) (js/RegExp. "^/(.*)/([a-z]*)$" "i"))]
    (if match
      (try
        (let [body (aget match 1)
              flags (aget match 2)
              final-flags (if (str/includes? flags "i") flags (str flags "i"))]
          (js/RegExp. body final-flags))
        (catch :default _ nil))
      (js/RegExp. (str "\\b" (escape-regexp term) "\\b") "i"))))

(defn- raw-entry->entry
  "Normalizes a raw watchlist entry (string or map) into a [label pattern-str]
  pair, or nil when unparseable."
  [entry]
  (cond
    (string? entry)
    [entry entry]

    (and (map? entry) (some? (:pattern entry)))
    (let [pattern-str (str (:pattern entry))
          label (if (str/blank? (:name entry)) pattern-str (str/trim (str (:name entry))))]
      [label pattern-str])

    (and (object? entry) (some? (aget entry "pattern")))
    (let [pattern-str (str (aget entry "pattern"))
          name (aget entry "name")
          label (if (str/blank? name) pattern-str (str/trim (str name)))]
      [label pattern-str])

    :else nil))

(defn compile-watch-entry
  "Compiles a pattern string and label into a WatchEntry map with a RegExp
  pattern. Returns nil for invalid patterns."
  [pattern-str label]
  (when-let [pattern (compile-regex pattern-str)]
    {:label label :pattern pattern}))

(defn compile-watch-entries
  "Compiles a sequence of raw watchlist entries into distinct WatchEntry maps,
  keeping the first entry for each label."
  [entries]
  (->> entries
       (map raw-entry->entry)
       (remove nil?)
       (map (fn [[label pattern-str]] (compile-watch-entry pattern-str label)))
       (remove nil?)
       (reduce (fn [acc entry]
                 (if (some #(= (:label entry) (:label %)) acc)
                   acc
                   (conj acc entry)))
               [])
       vec))

(defn moderation-hit?
  "True when a WatchEntry pattern matches message-text."
  [watch-entry message-text]
  (boolean (re-find (:pattern watch-entry) (str message-text))))

(defn moderation-hits
  "Returns the subsequence of WatchEntry values whose patterns match
  message-text."
  [watch-entries message-text]
  (->> watch-entries
       (filter #(moderation-hit? % message-text))
       vec))
