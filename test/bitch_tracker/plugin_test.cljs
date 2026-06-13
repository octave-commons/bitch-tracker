(ns bitch-tracker.plugin-test
  (:require [bitch-tracker.plugin :as plugin]
            [cljs.test :refer-macros [deftest is testing]]))

(def test-meta
  #js {:name "BitchTracker"
       :version "0.0.1"
       :description "Test metadata"})

(defn- restore-global! [k old-value]
  (if (undefined? old-value)
    (js-delete js/globalThis k)
    (aset js/globalThis k old-value)))

(defn- mock-bd-api! [load-fn]
  (aset js/globalThis "BdApi"
        #js {:Data #js {:load load-fn
                        :save (fn [_plugin _key _value] nil)}}))

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

(deftest message-event-builder-adds-moderation-watchlist-labels
  (testing "watchTerms from BetterDiscord settings become moderation labels"
    (let [old-bd-api (aget js/globalThis "BdApi")]
      (try
        (mock-bd-api! (fn [_plugin key]
                        (case key
                          "watchTerms" "badword"
                          nil)))
        (let [state #js {:guildStore nil :userStore nil}
              message #js {:id "m2"
                           :channel_id "c1"
                           :guild_id "1391832426048651334"
                           :content "that badword appears"
                           :timestamp "2026-05-20T18:19:17.953Z"
                           :author #js {:id "u2" :username "bob" :bot false}
                           :attachments #js []
                           :embeds #js []
                           :mentions #js []
                           :mention_roles #js []}
              channel #js {:id "c1" :name "general"}
              event (plugin/message-to-event state message channel "1391832426048651334")
              tags (aget event "meta" "tags")
              hits (aget event "extra" "moderation_watch_hits")]
          (is (some #{"moderation-watch"} (array-seq tags)))
          (is (some #{"badword"} (array-seq hits))))
        (finally
          (restore-global! "BdApi" old-bd-api))))))

(deftest reaction-event-builder-labels-quality-and-poodle-reactions
  (testing "reaction ingestion carries reaction and moderation labels"
    (let [reaction #js {:messageId "m1" :channelId "c1"}
          event (plugin/reaction-to-event #js {} reaction nil "g1" "🐩" "u1" nil)
          labels (aget event "extra" "openplanner_labels" "labels")]
      (is (= "discord.reaction" (aget event "kind")))
      (is (= "discord-moderation-watch-v1" (aget event "extra" "openplanner_labels" "claim_system")))
      (is (some #{"moderation-watch:poodle-label"} (array-seq labels))))))

(defn- fake-response [ok? status body retry-after]
  #js {:ok ok?
       :status status
       :headers #js {:get (fn [name]
                            (when (= name "retry-after") retry-after))}
       :text (fn [] (js/Promise.resolve body))})

(deftest reaction-event-builder-labels-quality-reactions
  (testing "quality reactions carry explicit quality metadata"
    (let [reaction #js {:messageId "m2" :channelId "c2"}
          event (plugin/reaction-to-event #js {} reaction nil "g1" "✅" "u1" nil)
          labels (aget event "extra" "openplanner_labels" "labels")]
      (is (= "discord-quality-v1" (aget event "extra" "openplanner_labels" "claim_system")))
      (is (= "good" (aget event "extra" "quality")))
      (is (some #{"quality:good"} (array-seq labels))))))

(deftest bot-send-redacts-token-and-parses-retry-after
  (testing "bot send helpers avoid leaking tokens and honor retry hints"
    (let [res (fake-response false 429 "{\"retry_after\":0.25}" nil)]
      (is (= "abc <bot-token> xyz" (plugin/redact-token "abc SECRET xyz" "SECRET")))
      (is (= 250 (plugin/bot-retry-after-ms res "{\"retry_after\":0.25}"))))))

(deftest ^:async bot-send-retries-rate-limits-and-5xx
  (testing "bot REST send retries 429/5xx before succeeding"
    (let [calls (atom 0)
          sleeps (atom [])
          fetch-impl (fn [_url _request]
                       (let [attempt (swap! calls inc)]
                         (js/Promise.resolve
                          (case attempt
                            1 (fake-response false 429 "{\"retry_after\":0.001}" nil)
                            2 (fake-response false 500 "upstream said SECRET" nil)
                            (fake-response true 200 "" nil)))))
          sleep-fn (fn [ms]
                     (swap! sleeps conj ms)
                     (js/Promise.resolve true))
          ok? (await (plugin/send-bot-request-with-retries! fetch-impl "https://discord.test" #js {} "SECRET" "test" sleep-fn 1 3))]
      (is (= true ok?))
      (is (= 3 @calls))
      (is (= [1 1000] @sleeps)))))

(deftest ^:async flush-preserves-queue-on-openplanner-failure
  (testing "failed OpenPlanner flush leaves queued events for retry"
    (let [old-bd-api (aget js/globalThis "BdApi")
          old-fetch (aget js/globalThis "fetch")
          queue #js [#js {:id "e1"}]
          state #js {:queue queue}]
      (try
        (mock-bd-api! (fn [_plugin key]
                        (case key
                          "apiKey" "KEY"
                          "endpoint" "https://openplanner.test/v1/events"
                          nil)))
        (aset js/globalThis "fetch"
              (fn [_url _request]
                (js/Promise.resolve #js {:ok false :status 500 :statusText "boom"})))
        (await (plugin/flush! state))
        (is (= 1 (.-length queue)))
        (finally
          (restore-global! "BdApi" old-bd-api)
          (restore-global! "fetch" old-fetch))))))
