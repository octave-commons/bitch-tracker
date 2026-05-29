(ns bitch-tracker.plugin-test
  (:require [bitch-tracker.plugin :as plugin]
            [cljs.test :refer-macros [deftest is testing]]))

(def test-meta
  #js {:name "BitchTracker"
       :version "0.0.1"
       :description "Test metadata"})

(deftest factory-returns-betterdiscord-lifecycle
  (testing "the exported value is the BetterDiscord meta factory contract"
    (let [instance (plugin/plugin-factory test-meta)]
      (is (fn? (.-start instance)))
      (is (fn? (.-stop instance)))
      (is (fn? (.-getSettingsPanel instance))))))

(deftest ^:async async-start-and-stop-lifecycle
  (testing "ClojureScript ^:async tests can await an async plugin start"
    (let [instance (plugin/plugin-factory test-meta)
          state (await (.start instance))]
      (is (= true (aget state "started")))
      (is (string? (aget state "startedAt")))
      (is (fn? (.-flush instance)))
      (is (fn? (.-runBackfill instance)))
      (.stop instance)
      (is (= false (aget state "started")))
      (is (string? (aget state "stoppedAt"))))))

(deftest message-event-builder-preserves-openplanner-shape
  (testing "pseudo OpenPlanner message payloads are emitted as JSON-ready events"
    (let [state #js {:guildStore nil :userStore nil}
          message #js {:id "m1"
                       :channel_id "c1"
                       :guild_id "1391832426048651334"
                       :content "hello"
                       :timestamp "2026-05-20T18:19:17.953Z"
                       :author #js {:id "u1" :username "alice" :bot false}
                       :attachments #js []
                       :embeds #js []
                       :mentions #js []
                       :mention_roles #js []}
          channel #js {:id "c1" :name "general"}
          event (plugin/message-to-event state message channel "1391832426048651334")]
      (is (= "openplanner.event.v1" (aget event "schema")))
      (is (= "discord.message" (aget event "kind")))
      (is (= "hello" (aget event "text")))
      (is (= "alice" (aget event "meta" "author")))
      (is (= "c1" (aget event "extra" "channel_id"))))))

(deftest reaction-event-builder-labels-quality-and-poodle-reactions
  (testing "reaction ingestion carries reaction and moderation labels"
    (let [reaction #js {:messageId "m1" :channelId "c1"}
          event (plugin/reaction-to-event #js {} reaction nil "g1" "🐩" "u1" nil)
          labels (aget event "extra" "openplanner_labels" "labels")]
      (is (= "discord.reaction" (aget event "kind")))
      (is (= "discord-moderation-watch-v1" (aget event "extra" "openplanner_labels" "claim_system")))
      (is (some #{"moderation-watch:poodle-label"} (array-seq labels))))))
