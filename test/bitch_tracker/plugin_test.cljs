(ns bitch-tracker.plugin-test
  (:require [clojure.string :as str]
            [bitch-tracker.plugin :as plugin]
            [bitch-tracker.shared.events :as events]
            [bitch-tracker.shared.util :as u]
            [bitch-tracker.bot.dedup :as dedup]
            [bitch-tracker.bot.socket :as socket]
            [cljs.test :refer-macros [deftest is testing]]))

(def test-meta
  #js {:name "BitchTracker"
       :version "0.0.1"
       :description "Test metadata"})

(defn- restore-global! [k old-value]
  (if (undefined? old-value)
    (js-delete js/globalThis k)
    (aset js/globalThis k old-value)))

(defn- mock-bd-api! []
  (aset js/globalThis "BdApi"
        #js {:Data #js {:load (fn [_plugin _key] nil)
                        :save (fn [_plugin _key _value] nil)}}))

(deftest factory-returns-betterdiscord-lifecycle
  (testing "the exported value is the BetterDiscord meta factory contract"
    (let [instance (plugin/plugin-factory test-meta)]
      (is (fn? (.-start instance)))
      (is (fn? (.-stop instance)))
      (is (fn? (.-getSettingsPanel instance)))
      (is (fn? (.-flush instance)))
      (is (fn? (.-runBackfill instance))))))

(deftest ^:async async-start-and-stop-lifecycle
  (testing "ClojureScript ^:async tests can await an async plugin start"
    (let [old-bd-api (aget js/globalThis "BdApi")]
      (try
        (mock-bd-api!)
        (let [instance (plugin/plugin-factory test-meta)
              state (await (.start instance))]
          (is (= true (aget state "started")))
          (is (string? (aget state "startedAt")))
          (is (fn? (.-flush instance)))
          (is (fn? (.-runBackfill instance)))
          (.stop instance)
          (is (= false (aget state "started")))
          (is (string? (aget state "stoppedAt"))))
        (finally
          (restore-global! "BdApi" old-bd-api))))))

(deftest message-event-builder-preserves-openplanner-shape
  (testing "pseudo OpenPlanner message payloads are emitted as JSON-ready events"
    (let [message #js {:id "m1"
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
          event (events/message-to-event message channel "1391832426048651334")]
      (is (= "openplanner.event.v1" (aget event "schema")))
      (is (= "discord.message" (aget event "kind")))
      (is (= "hello" (aget event "text")))
      (is (= "alice" (aget event "meta" "author")))
      (is (= "c1" (aget event "extra" "channel_id"))))))

(deftest message-event-builder-tags-known-watch-user
  (testing "known watch users are tagged in event metadata"
    (let [message #js {:id "m2"
                       :channel_id "c1"
                       :guild_id "1391832426048651334"
                       :content "hello"
                       :timestamp "2026-05-20T18:19:17.953Z"
                       :author #js {:id "59259128266100736" :username "watched" :bot false}
                       :attachments #js []
                       :embeds #js []
                       :mentions #js []
                       :mention_roles #js []}
          channel #js {:id "c1" :name "general"}
          event (events/message-to-event message channel "1391832426048651334")]
      (is (true? (aget event "extra" "is_known_watch_user")))
      (is (some #{"known-watch-user"} (array-seq (aget event "meta" "tags")))))))

(deftest reaction-event-builder-labels-poodle-reactions
  (testing "reaction ingestion carries reaction and moderation labels"
    (let [reaction #js {:messageId "m1" :channelId "c1"}
          event (events/reaction-to-event reaction nil "g1" "🐩" "u1" nil)
          labels (aget event "extra" "openplanner_labels" "labels")]
      (is (= "discord.reaction" (aget event "kind")))
      (is (= "discord-moderation-watch-v1" (aget event "extra" "openplanner_labels" "claim_system")))
      (is (some #{"moderation-watch:poodle-label"} (array-seq labels))))))

(deftest reaction-event-builder-labels-quality-reactions
  (testing "quality reactions carry explicit quality metadata"
    (let [reaction #js {:messageId "m2" :channelId "c2"}
          event (events/reaction-to-event reaction nil "g1" "✅" "u1" nil)
          labels (aget event "extra" "openplanner_labels" "labels")]
      (is (= "discord-quality-v1" (aget event "extra" "openplanner_labels" "claim_system")))
      (is (= "good" (aget event "extra" "quality")))
      (is (some #{"quality:good"} (array-seq labels))))))

(deftest discord-message-chunks-splits-long-messages
  (testing "long messages are split at newline boundaries when possible"
    (let [chunks (u/discord-message-chunks (str/join "\n" (repeat 500 "line")))]
      (is (> (count chunks) 1))
      (is (every? #(<= (count %) 2000) chunks)))))

(deftest sanitize-mentions-zero-width-breaks-pings
  (testing "@everyone, @here, and mentions are defanged"
    (is (= "@\u200beveryone" (u/sanitize-mentions "@everyone")))
    (is (= "@\u200bhere" (u/sanitize-mentions "@here")))
    (is (= "<@\u200b123>" (u/sanitize-mentions "<@123>")))))

(deftest dedup-rejects-duplicate-event-ids
  (testing "bot dedup cache rejects duplicate event ids"
    (let [state (dedup/make-state)]
      (is (dedup/add! state "discord:g1:c1:m1" 1000 100))
      (is (not (dedup/add! state "discord:g1:c1:m1" 1000 100)))
      (is (= 1 (.-misses state)))
      (is (= 1 (.-hits state))))))

(deftest dedup-prunes-by-age-and-size
  (testing "dedup cache evicts stale entries and caps size"
    (let [state (dedup/make-state)]
      (is (dedup/add! state "old" 1 100))
      (js/setTimeout (fn []
                       (is (dedup/add! state "new" 1 100))
                       (is (not (dedup/seen? state "old")))
                       (is (dedup/seen? state "new")))
                     5)
      (let [size-state (dedup/make-state)]
        (dotimes [i 5]
          (dedup/add! size-state (str "id" i) 1000000 3))
        (is (<= (.-size (.-cache size-state)) 3))))))

(deftest socket-label-count-pools-multiple-reactors
  (testing "multiple reactors labeling the same message author each contribute to the count"
    (let [state (socket/make-state)
          message #js {:id "m1" :channel_id "c1" :guild_id "g1"
                       :content "bad" :timestamp "2026-05-20T18:19:17.953Z"
                       :author #js {:id "u1" :username "alice"}}
          data-fn (fn [reactor-id]
                    #js {:userId "u1" :messageId "m1" :reactorId reactor-id
                         :message message :channel #js {:id "c1" :name "general"}
                         :guildId "g1"})]
      (socket/handle-label-added! state (data-fn "r1"))
      (socket/handle-label-added! state (data-fn "r2"))
      (socket/handle-label-added! state (data-fn "r1")) ;; duplicate reactor
      (is (= 2 (socket/label-count state "u1"))))))

(deftest socket-label-remove-decrements-pooled-count
  (testing "removing one reactor's label decrements the pooled count"
    (let [state (socket/make-state)
          message #js {:id "m1" :channel_id "c1" :guild_id "g1"
                       :content "bad" :timestamp "2026-05-20T18:19:17.953Z"
                       :author #js {:id "u1" :username "alice"}}]
      (socket/handle-label-added! state #js {:userId "u1" :messageId "m1" :reactorId "r1"
                                              :message message :channel #js {:id "c1" :name "general"}
                                              :guildId "g1"})
      (socket/handle-label-added! state #js {:userId "u1" :messageId "m1" :reactorId "r2"
                                              :message message :channel #js {:id "c1" :name "general"}
                                              :guildId "g1"})
      (is (= 2 (socket/label-count state "u1")))
      (socket/handle-label-removed! state #js {:userId "u1" :messageId "m1" :reactorId "r1"})
      (is (= 1 (socket/label-count state "u1")))
      (socket/handle-label-removed! state #js {:userId "u1" :messageId "m1" :reactorId "r2"})
      (is (= 0 (socket/label-count state "u1"))))))

(deftest socket-incoming-events-are-deduped-before-queueing
  (testing "identical event ids from multiple plugins are only queued once"
    (let [socket-state (socket/make-state)
          dedup-state (dedup/make-state)
          op-state #js {:queue #js []}
           config {:max-persisted-events 100}
          event #js {:id "discord:g1:c1:m1"
                     :text "hello"
                     :extra #js {:guild_id "g1" :channel_id "c1" :message_id "m1"}
                     :meta #js {:author "alice" :author_id "u1"}}]
      (set! (.-dedup-state socket-state) dedup-state)
      (set! (.-op-state socket-state) op-state)
      (set! (.-config socket-state) config)
      (socket/handle-incoming-event! socket-state event)
      (socket/handle-incoming-event! socket-state event)
      (is (= 1 (.-length (.-queue op-state)))))))
