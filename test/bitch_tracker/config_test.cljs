(ns bitch-tracker.config-test
  (:require [bitch-tracker.bot.config :as cfg]
            [bitch-tracker.bot.discord :as discord]
            [cljs.test :refer-macros [deftest is testing]]))

(deftest watch-pattern-parses-regex-string
  (testing "slash-delimited regex strings compile to labeled patterns"
    (let [entry (cfg/watch-pattern "/foo/i")]
      (is (= "/foo/i" (:label entry)))
      (is (true? (.test (:pattern entry) "FOO")))))
  (testing "plain terms compile to case-insensitive word-boundary patterns"
    (let [entry (cfg/watch-pattern "badword")]
      (is (= "badword" (:label entry)))
      (is (true? (.test (:pattern entry) "badword")))
      (is (false? (.test (:pattern entry) "abadwordx"))))))

(deftest watch-entries-understands-named-pattern-objects
  (testing "named pattern objects produce label from name field"
    (let [watchlist #js {:watchTerms #js []
                         :watchRegexes #js []
                         :patterns #js [#js {:name "N slur" :pattern "/\\bn+\\b/i"}]}
          entries (cfg/watch-entries watchlist)]
      (is (= 1 (count entries)))
      (is (= "N slur" (:label (first entries))))
      (is (true? (.test (:pattern (first entries)) "nnn")))))
  (testing "legacy regex strings remain supported"
    (let [watchlist #js {:watchTerms #js []
                         :watchRegexes #js ["/foo/i"]
                         :patterns #js []}
          entries (cfg/watch-entries watchlist)]
      (is (= 1 (count entries)))
      (is (= "/foo/i" (:label (first entries))))))
  (testing "literal terms remain supported"
    (let [watchlist #js {:watchTerms #js ["badword"]
                         :watchRegexes #js []
                         :patterns #js []}
          entries (cfg/watch-entries watchlist)]
      (is (= 1 (count entries)))
      (is (= "badword" (:label (first entries))))))
  (testing "duplicates are collapsed by label"
    (let [watchlist #js {:watchTerms #js ["foo"]
                         :watchRegexes #js []
                         :patterns #js [#js {:name "foo" :pattern "/foo/i"}
                                        #js {:name "foo" :pattern "/foo/gi"}]}
          entries (cfg/watch-entries watchlist)]
      (is (= 1 (count entries)))))
  (testing "different regex strings with the same name are collapsed to the first"
    (let [watchlist #js {:watchRegexes #js ["/foo/i"]
                         :patterns #js [#js {:name "/foo/i" :pattern "/foo/i"}]}
          entries (cfg/watch-entries watchlist)]
      (is (= 1 (count entries)))))
  (testing "pattern objects without a name fall back to the pattern string"
    (let [watchlist #js {:patterns #js [#js {:pattern "/\\btest\\b/i"}]}
          entries (cfg/watch-entries watchlist)]
      (is (= "/\\btest\\b/i" (:label (first entries)))))))

(deftest highlight-matches-wraps-matched-substrings
  (testing "single match is wrapped in bold markdown"
    (let [matches [{:label "N slur" :index 6 :matched "nigger" :length 6}]
          highlighted (discord/highlight-matches "hello nigger there" matches)]
      (is (= "hello **nigger** there" highlighted))))
  (testing "multiple matches are highlighted at correct positions"
    (let [matches [{:label "N slur" :index 6 :matched "nigger" :length 6}
                   {:label "F slur" :index 17 :matched "faggot" :length 6}]
          highlighted (discord/highlight-matches "hello nigger and faggot there" matches)]
      (is (= "hello **nigger** and **faggot** there" highlighted))))
  (testing "matches are sorted by index descending to preserve positions"
    (let [matches [{:label "F slur" :index 17 :matched "faggot" :length 6}
                   {:label "N slur" :index 6 :matched "nigger" :length 6}]
          highlighted (discord/highlight-matches "hello nigger and faggot there" matches)]
      (is (= "hello **nigger** and **faggot** there" highlighted)))))

(deftest format-tracker-message-includes-match-details
  (testing "tracker message with matches shows highlighted content and match metadata"
    (let [message #js {:id "m1"
                       :channel_id "c1"
                       :guild_id "g1"
                       :content "hello nigger there"
                       :timestamp "2026-05-20T18:19:17.953Z"
                       :author #js {:id "u1" :username "alice"}}
          channel #js {:id "c1" :name "general"}
          matches [{:label "N slur" :index 6 :matched "nigger" :length 6}]
          text (discord/format-tracker-message message channel "g1" "Test Server" "moderation-watch: N slur" matches)]
      (is (re-find #"\*\*Message:\*\* hello \*\*nigger\*\* there" text))
      (is (re-find #"\*\*Matches:\*\* N slur \(`nigger` at index 6\)" text))))
  (testing "tracker message without matches falls back to plain content"
    (let [message #js {:id "m1"
                       :channel_id "c1"
                       :guild_id "g1"
                       :content "hello there"
                       :timestamp "2026-05-20T18:19:17.953Z"
                       :author #js {:id "u1" :username "alice"}}
          channel #js {:id "c1" :name "general"}
          text (discord/format-tracker-message message channel "g1" "Test Server" "known-watch-user" nil)]
      (is (re-find #"\*\*Message:\*\* hello there" text))
      (is (nil? (re-find #"\*\*Matches:\*\*" text))))))
