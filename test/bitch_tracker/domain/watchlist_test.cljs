(ns bitch-tracker.domain.watchlist-test
  "Tests for bitch-tracker.domain.watchlist.

  Each test is a validated fact about pure watchlist compilation and matching."
  (:require [cljs.test :refer [deftest is testing]]
            [bitch-tracker.domain.watchlist :as wl]))

;; ---------------------------------------------------------------------------
;; compile-watch-entry
;; ---------------------------------------------------------------------------

(deftest compile-watch-entry-produces-regexp-for-plain-term
  (testing "a plain string term compiles to a WatchEntry with a RegExp"
    (let [entry (wl/compile-watch-entry "slur" "slur")]
      (is (some? entry))
      (is (instance? js/RegExp (:pattern entry)))
      (is (= "slur" (:label entry))))))

(deftest compile-watch-entry-produces-regexp-for-slash-literal
  (testing "a /regex/ term compiles to a WatchEntry with the given pattern"
    (let [entry (wl/compile-watch-entry "/bad\\s+word/i" "bad word")]
      (is (some? entry))
      (is (instance? js/RegExp (:pattern entry))))))

(deftest compile-watch-entry-returns-nil-for-invalid-regex
  (testing "an invalid regex literal returns nil instead of throwing"
    ;; An unbalanced bracket group is a compile-time error in JS RegExp
    (let [entry (wl/compile-watch-entry "/[invalid/" "broken")]
      (is (nil? entry)))))

;; ---------------------------------------------------------------------------
;; compile-watch-entries
;; ---------------------------------------------------------------------------

(deftest compile-watch-entries-compiles-string-entries
  (testing "string entries are compiled with the string as both label and term"
    (let [entries (wl/compile-watch-entries ["hate" "slur"])]
      (is (= 2 (count entries)))
      (is (every? #(instance? js/RegExp (:pattern %)) entries)))))

(deftest compile-watch-entries-compiles-map-entries
  (testing "map entries with :pattern and :name keys compile correctly"
    (let [entries (wl/compile-watch-entries [{:name "bad-word" :pattern "hate"}])]
      (is (= 1 (count entries)))
      (is (= "bad-word" (:label (first entries)))))))

(deftest compile-watch-entries-deduplicates-by-label
  (testing "two entries with the same label produce only one result"
    (let [entries (wl/compile-watch-entries ["hate" "hate"])]
      (is (= 1 (count entries))))))

(deftest compile-watch-entries-skips-unparseable-entries
  (testing "nil entries and unrecognised shapes are silently dropped"
    (let [entries (wl/compile-watch-entries [nil 42 "ok-term"])]
      (is (= 1 (count entries))))))

;; ---------------------------------------------------------------------------
;; moderation-hit?
;; ---------------------------------------------------------------------------

(deftest moderation-hit-true-for-matching-text
  (testing "moderation-hit? is true when the entry pattern matches"
    (let [entry (wl/compile-watch-entry "hate" "hate")]
      (is (wl/moderation-hit? entry "I hate this")))))

(deftest moderation-hit-false-for-non-matching-text
  (testing "moderation-hit? is false when the pattern does not match"
    (let [entry (wl/compile-watch-entry "hate" "hate")]
      (is (not (wl/moderation-hit? entry "everything is fine"))))))

(deftest moderation-hit-is-case-insensitive
  (testing "the compiled pattern is case-insensitive"
    (let [entry (wl/compile-watch-entry "HATE" "HATE")]
      (is (wl/moderation-hit? entry "I HATE this"))
      (is (wl/moderation-hit? entry "i hate this")))))

;; ---------------------------------------------------------------------------
;; moderation-hits
;; ---------------------------------------------------------------------------

(deftest moderation-hits-returns-matching-entries
  (testing "moderation-hits returns only entries that match the message"
    (let [entries (wl/compile-watch-entries ["hate" "love" "fear"])
          hits (wl/moderation-hits entries "I hate and fear this")]
      (is (= 2 (count hits)))
      (is (some #(= "hate" (:label %)) hits))
      (is (some #(= "fear" (:label %)) hits)))))

(deftest moderation-hits-returns-empty-when-no-match
  (testing "moderation-hits returns an empty vector when nothing matches"
    (let [entries (wl/compile-watch-entries ["hate"])]
      (is (= [] (wl/moderation-hits entries "everything is fine"))))))
