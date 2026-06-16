(ns bitch-tracker.domain.watchlist-test
  "Tests for bitch-tracker.domain.watchlist."
  (:require [bitch-tracker.domain.watchlist :as watchlist]
            [cljs.test :refer-macros [deftest is testing]]))

(deftest compile-watch-entry-parses-plain-term
  (testing "plain terms compile to case-insensitive word-boundary patterns"
    (let [entry (watchlist/compile-watch-entry "badword" "badword")]
      (is (= "badword" (:label entry)))
      (is (true? (.test (:pattern entry) "badword")))
      (is (false? (.test (:pattern entry) "abadwordx"))))))

(deftest compile-watch-entry-parses-regex-string
  (testing "slash-delimited regex strings compile to labeled RegExp objects"
    (let [entry (watchlist/compile-watch-entry "/foo/i" "/foo/i")]
      (is (= "/foo/i" (:label entry)))
      (is (true? (.test (:pattern entry) "FOO"))))))

(deftest compile-watch-entry-returns-nil-for-invalid-regex
  (testing "invalid regex literals produce no entry"
    (is (nil? (watchlist/compile-watch-entry "/(/" "bad")))))

(deftest compile-watch-entries-handles-strings-and-maps
  (testing "mixed raw entries compile into WatchEntry maps"
    (let [entries (watchlist/compile-watch-entries ["badword"
                                                    {:name "N slur" :pattern "/\\bn+\\b/i"}])]
      (is (= 2 (count entries)))
      (is (= #{"badword" "N slur"} (set (map :label entries)))))))

(deftest compile-watch-entries-deduplicates-by-label
  (testing "entries with duplicate labels collapse to the first occurrence"
    (let [entries (watchlist/compile-watch-entries ["foo"
                                                    {:name "foo" :pattern "/foo/i"}])]
      (is (= 1 (count entries))))))

(deftest compile-watch-entries-uses-pattern-fallback-label
  (testing "unnamed pattern objects use the pattern string as the label"
    (let [entries (watchlist/compile-watch-entries [{:pattern "/\\btest\\b/i"}])]
      (is (= 1 (count entries)))
      (is (= "/\\btest\\b/i" (:label (first entries)))))))

(deftest moderation-hit?-matches
  (testing "hit? is true when the entry pattern matches the message text"
    (let [entry (watchlist/compile-watch-entry "badword" "badword")]
      (is (watchlist/moderation-hit? entry "this is badword here"))
      (is (not (watchlist/moderation-hit? entry "this is fine"))))))

(deftest moderation-hits-returns-matching-entries
  (testing "hits returns only the WatchEntry values that match"
    (let [entries (watchlist/compile-watch-entries ["badword" "goodword"])
          hits (watchlist/moderation-hits entries "badword")]
      (is (= 1 (count hits)))
      (is (= "badword" (:label (first hits)))))))

(deftest moderation-hits-empty-when-no-match
  (testing "hits returns an empty vector when nothing matches"
    (let [entries (watchlist/compile-watch-entries ["badword"])
          hits (watchlist/moderation-hits entries "clean text")]
      (is (empty? hits))
      (is (vector? hits)))))
