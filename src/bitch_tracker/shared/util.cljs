(ns bitch-tracker.shared.util
  (:require [clojure.string :as str]))

(defn now-iso []
  (.toISOString (js/Date.)))

(defn present-string? [value]
  (and (string? value) (not (str/blank? value))))

(defn jget
  ([obj k]
   (when (some? obj) (aget obj k)))
  ([obj k & ks]
   (reduce jget (jget obj k) ks)))

(defn jcall [obj method & args]
  (when-let [f (jget obj method)]
    (.apply f obj (into-array args))))

(defn js-values [value]
  (cond
    (nil? value) []
    (array? value) (array-seq value)
    (jget value "toArray") (array-seq (jcall value "toArray"))
    (jget value "values") (array-seq (js/Array.from (jcall value "values")))
    :else (array-seq (js/Object.values value))))

(defn parse-json [raw fallback]
  (try
    (if (present-string? raw) (js/JSON.parse raw) fallback)
    (catch :default _err fallback)))

(defn nonce []
  (str (.getTime (js/Date.)) "-" (subs (.toString (js/Math.random) 36) 2)))

(defn escape-regexp [value]
  (.replace (str value) (js/RegExp. "[.*+?^${}()|[\\]\\\\]" "g") "\\$&"))

(defn sanitize-mentions [text]
  (-> (str text)
      (str/replace "@everyone" "@\u200beveryone")
      (str/replace "@here" "@\u200bhere")
      (str/replace #"<@!?([0-9]+)>" "<@\u200b$1>")
      (str/replace #"<@&([0-9]+)>" "<@&\u200b$1>")
      (str/replace #"<#([0-9]+)>" "<#\u200b$1>")))

(defn discord-timestamp [value]
  (let [date (if value (js/Date. value) (js/Date.))
        ms (if (js/isNaN (.getTime date)) (.getTime (js/Date.)) (.getTime date))]
    (str "<t:" (js/Math.floor (/ ms 1000)) ":F>")))

(defn discord-message-chunks [content]
  (let [text (str content)]
    (if (<= (count text) 1900)
      [(if (str/blank? text) "(empty message)" text)]
      (loop [chunks [] rest text]
        (if (<= (count rest) 1900)
          (let [all (cond-> chunks (not (str/blank? rest)) (conj rest))]
            (map-indexed #(if (= 1 (count all)) %2 (str "(" (inc %1) "/" (count all) ")\n" %2)) all))
          (let [newline-index (.lastIndexOf rest "\n" 1900)
                split-at (if (< newline-index 500) 1900 newline-index)]
            (recur (conj chunks (subs rest 0 split-at))
                   (str/replace (subs rest split-at) #"^\n+" ""))))))))

(defn reaction-label [emoji]
  (let [value (str/trim (str emoji))
        custom-match (.match value (js/RegExp. "^<a?:([^:>]+):([0-9]+)>$"))]
    (cond
      (str/blank? value) "unknown"
      custom-match (str "custom:" (aget custom-match 1) ":" (aget custom-match 2))
      (.test (js/RegExp. "^[0-9]+$") value) (str "custom:" value)
      :else (str "unicode:" (str/join "-" (map #(.toString (.codePointAt % 0) 16) (array-seq (js/Array.from value))))))))

(defn quality-from-emoji [emoji]
  (case (str emoji)
    ("✅" "☑️" "✔️" "✔") "good"
    ("❌" "✖️" "✖" "❎") "bad"
    nil))
