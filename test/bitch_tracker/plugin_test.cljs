(ns bitch-tracker.plugin-test
  "Tests for the BetterDiscord plugin factory and shared event builders."
  (:require [bitch-tracker.bot.dedup :as dedup]
            [bitch-tracker.bot.socket :as socket]
            [bitch-tracker.plugin :as plugin]
            [bitch-tracker.shared.events :as events]
            [bitch-tracker.shared.util :as u]
            [cljs.test :as t]
            [clojure.string :as str]))

(def test-meta
  "Sample BetterDiscord plugin metadata used across tests."
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

;; Tests that the plugin factory exposes the expected BetterDiscord lifecycle methods.
(t/deftest factory-returns-betterdiscord-lifecycle
  (t/testing "the exported value is the BetterDiscord meta factory contract"
    (let [instance (plugin/plugin-factory test-meta)]
      (t/is (fn? (.-start instance)))
      (t/is (fn? (.-stop instance)))
      (t/is (fn? (.-getSettingsPanel instance)))
      (t/is (fn? (.-flush instance)))
      (t/is (fn? (.-runBackfill instance))))))

;; Tests that the async start/stop lifecycle toggles plugin state correctly.
(t/deftest ^:async async-start-and-stop-lifecycle
  (t/testing "ClojureScript ^:async tests can await an async plugin start"
    (let [old-bd-api (aget js/globalThis "BdApi")]
      (try
        (mock-bd-api!)
        (let [instance (plugin/plugin-factory test-meta)
              state (await (.start instance))]
          (t/is (= true (aget state "started")))
          (t/is (string? (aget state "startedAt")))
          (t/is (fn? (.-flush instance)))
          (t/is (fn? (.-runBackfill instance)))
          (.stop instance)
          (t/is (= false (aget state "started")))
          (t/is (string? (aget state "stoppedAt"))))
        (finally
          (restore-global! "BdApi" old-bd-api))))))

;; Tests that Discord messages are shaped into OpenPlanner message events.
(t/deftest message-event-builder-preserves-openplanner-shape
  (t/testing "pseudo OpenPlanner message payloads are emitted as JSON-ready events"
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
          event (events/message-to-event message channel "1391832426048651334" #{})]
      (t/is (= "openplanner.event.v1" (aget event "schema")))
      (t/is (= "discord.message" (aget event "kind")))
      (t/is (= "hello" (aget event "text")))
      (t/is (= "alice" (aget event "meta" "author")))
      (t/is (= "c1" (aget event "extra" "channel_id"))))))

;; Tests that messages from known watch users are tagged in event metadata.
(t/deftest message-event-builder-tags-known-watch-user
  (t/testing "known watch users are tagged in event metadata"
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
          event (events/message-to-event message channel "1391832426048651334" #{"59259128266100736"})]
      (t/is (true? (aget event "extra" "is_known_watch_user")))
      (t/is (some #{"known-watch-user"} (array-seq (aget event "meta" "tags")))))))

;; Tests that poodle reactions are labeled as moderation watch events.
(t/deftest reaction-event-builder-labels-poodle-reactions
  (t/testing "reaction ingestion carries reaction and moderation labels"
    (let [reaction #js {:messageId "m1" :channelId "c1"}
          event (events/reaction-to-event reaction nil "g1" "🐩" "u1" nil)
          labels (aget event "extra" "openplanner_labels" "labels")]
      (t/is (= "discord.reaction" (aget event "kind")))
      (t/is (= "discord-moderation-watch-v1" (aget event "extra" "openplanner_labels" "claim_system")))
      (t/is (some #{"moderation-watch:poodle-label"} (array-seq labels))))))

;; Tests that quality reactions carry explicit quality metadata.
(t/deftest reaction-event-builder-labels-quality-reactions
  (t/testing "quality reactions carry explicit quality metadata"
    (let [reaction #js {:messageId "m2" :channelId "c2"}
          event (events/reaction-to-event reaction nil "g1" "✅" "u1" nil)
          labels (aget event "extra" "openplanner_labels" "labels")]
      (t/is (= "discord-quality-v1" (aget event "extra" "openplanner_labels" "claim_system")))
      (t/is (= "good" (aget event "extra" "quality")))
      (t/is (some #{"quality:good"} (array-seq labels))))))

;; Tests that long Discord messages are split into chunks at most 2000 characters.
(t/deftest discord-message-chunks-splits-long-messages
  (t/testing "long messages are split at newline boundaries when possible"
    (let [chunks (u/discord-message-chunks (str/join "\n" (repeat 500 "line")))]
      (t/is (> (count chunks) 1))
      (t/is (every? #(<= (count %) 2000) chunks)))))

;; Tests that @everyone, @here, and user mentions are defanged.
(t/deftest sanitize-mentions-zero-width-breaks-pings
  (t/testing "@everyone, @here, and mentions are defanged"
    (t/is (= "@\u200beveryone" (u/sanitize-mentions "@everyone")))
    (t/is (= "@\u200bhere" (u/sanitize-mentions "@here")))
    (t/is (= "<@\u200b123>" (u/sanitize-mentions "<@123>")))))

;; Tests that the deduplication cache rejects duplicate event ids.
(t/deftest dedup-rejects-duplicate-event-ids
  (t/testing "bot dedup cache rejects duplicate event ids"
    (let [state (dedup/make-state)]
      (t/is (dedup/add! state "discord:g1:c1:m1" 1000 100))
      (t/is (not (dedup/add! state "discord:g1:c1:m1" 1000 100)))
      (t/is (= 1 (.-misses state)))
      (t/is (= 1 (.-hits state))))))

;; Tests that the dedup cache evicts stale entries and caps its size.
(t/deftest ^:async dedup-prunes-by-age-and-size
  (t/testing "dedup cache evicts stale entries and caps size"
    (let [state (dedup/make-state)]
      (t/is (dedup/add! state "old" 1 100))
      (js/setTimeout (fn []
                       (t/is (dedup/add! state "new" 1 100))
                       (t/is (not (dedup/seen? state "old")))
                       (t/is (dedup/seen? state "new")))
                     5)
      (let [size-state (dedup/make-state)]
        (dotimes [i 5]
          (dedup/add! size-state (str "id" i) 1000000 3))
        (t/is (<= (.-size (.-cache size-state)) 3))))))

;; Tests that multiple reactors labeling the same author each contribute to the count.
(t/deftest socket-label-count-pools-multiple-reactors
  (t/testing "multiple reactors labeling the same message author each contribute to the count"
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
      (t/is (= 2 (socket/label-count state "u1"))))))

;; Tests that removing one reactor's label decrements the pooled label count.
(t/deftest socket-label-remove-decrements-pooled-count
  (t/testing "removing one reactor's label decrements the pooled count"
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
      (t/is (= 2 (socket/label-count state "u1")))
      (socket/handle-label-removed! state #js {:userId "u1" :messageId "m1" :reactorId "r1"})
      (t/is (= 1 (socket/label-count state "u1")))
      (socket/handle-label-removed! state #js {:userId "u1" :messageId "m1" :reactorId "r2"})
      (t/is (= 0 (socket/label-count state "u1"))))))

;; Tests that identical incoming event ids are only queued once.
(t/deftest socket-incoming-events-are-deduped-before-queueing
  (t/testing "identical event ids from multiple plugins are only queued once"
    (let [socket-state (socket/make-state)
          dedup-state (dedup/make-state)
          op-state #js {:queue #js []}
          config {:max-persisted-events 100}
          event #js {:id "discord:g1:c1:m1"
                     :text "hello"
                     :extra #js {:guild_id "g1" :channel_id "c1" :message_id "m1"}
                     :meta #js {:author "alice" :author_id "u1"}}]
      (set! (.-dedup_state socket-state) dedup-state)
      (set! (.-op_state socket-state) op-state)
      (set! (.-config socket-state) config)
      (socket/handle-incoming-event! socket-state event)
      (socket/handle-incoming-event! socket-state event)
      (t/is (= 1 (.-length (.-queue op-state)))))))
