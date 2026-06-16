(ns bitch-tracker.shared.util
  (:require [clojure.string :as str]))

(defn now-iso
  "Return the current time as an ISO-8601 string."
  []
  (.toISOString (js/Date.)))

(defn present-string?
  "Return true if value is a non-blank string."
  [value]
  (and (string? value) (not (str/blank? value))))

(defn jget
  "Get a nested JavaScript property from obj by key path."
  ([obj k]
   (when (some? obj) (aget obj k)))
  ([obj k & ks]
   (reduce jget (jget obj k) ks)))

(defn jcall
  "Call method on obj with args, returning nil if the method is missing."
  [obj method & args]
  (when-let [f (jget obj method)]
    (.apply f obj (into-array args))))

(defn js-values
  "Return a sequence of values from a JavaScript collection."
  [value]
  (cond
    (nil? value) []
    (array? value) (array-seq value)
    (jget value "toArray") (array-seq (jcall value "toArray"))
    (jget value "values") (array-seq (js/Array.from (jcall value "values")))
    :else (array-seq (js/Object.values value))))

(defn parse-json
  "Parse raw JSON, returning fallback on blank input or parse error."
  [raw fallback]
  (try
    (if (present-string? raw) (js/JSON.parse raw) fallback)
    (catch :default _err fallback)))

(defn nonce
  "Return a reasonably unique string nonce based on timestamp and randomness."
  []
  (str (.getTime (js/Date.)) "-" (subs (.toString (js/Math.random) 36) 2)))

(defn escape-regexp
  "Escape regex metacharacters in value."
  [value]
  (.replace (str value) (js/RegExp. "[.*+?^${}()|[\\]\\\\]" "g") "\\$&"))

(defn sanitize-mentions
  "Insert zero-width spaces into Discord mentions to prevent pinging."
  [text]
  (-> (str text)
      (str/replace "@everyone" "@\u200beveryone")
      (str/replace "@here" "@\u200bhere")
      (str/replace #"<@!?([0-9]+)>" "<@\u200b$1>")
      (str/replace #"<@&([0-9]+)>" "<@&\u200b$1>")
      (str/replace #"<#([0-9]+)>" "<#\u200b$1>")))

(defn discord-timestamp
  "Format a value as a Discord full timestamp marker, defaulting to now."
  [value]
  (let [date (if value (js/Date. value) (js/Date.))
        ms (if (js/isNaN (.getTime date)) (.getTime (js/Date.)) (.getTime date))]
    (str "<t:" (js/Math.floor (/ ms 1000)) ":F>")))

(defn discord-message-chunks
  "Split content into Discord-safe message chunks of at most 1900 characters."
  [content]
  (let [text (str content)]
    (if (<= (count text) 1900)
      [(if (str/blank? text) "(empty message)" text)]
      (loop [chunks [] remaining text]
        (if (<= (count remaining) 1900)
          (let [all (cond-> chunks (not (str/blank? remaining)) (conj remaining))]
            (map-indexed #(if (= 1 (count all)) %2 (str "(" (inc %1) "/" (count all) ")\n" %2)) all))
          (let [newline-index (.lastIndexOf remaining "\n" 1900)
                split-idx (if (< newline-index 500) 1900 newline-index)]
            (recur (conj chunks (subs remaining 0 split-idx))
                   (str/replace (subs remaining split-idx) #"^\n+" ""))))))))

(defn reaction-label
  "Return a namespaced label string for a Discord emoji."
  [emoji]
  (let [value (str/trim (str emoji))
        custom-match (.match value (js/RegExp. "^<a?:([^:>]+):([0-9]+)>$"))]
    (cond
      (str/blank? value) "unknown"
      custom-match (str "custom:" (aget custom-match 1) ":" (aget custom-match 2))
      (.test (js/RegExp. "^[0-9]+$") value) (str "custom:" value)
      :else (str "unicode:" (str/join "-" (map #(.toString (.codePointAt % 0) 16) (array-seq (js/Array.from value))))))))

(defn quality-from-emoji
  "Map a quality emoji to 'good' or 'bad', returning nil for others."
  [emoji]
  (case (str emoji)
    ("✅" "☑️" "✔️" "✔") "good"
    ("❌" "✖️" "✖" "❎") "bad"
    nil))
