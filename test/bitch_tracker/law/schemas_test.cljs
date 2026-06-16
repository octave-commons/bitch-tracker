(ns bitch-tracker.law.schemas-test
  "Tests for bitch-tracker.law.schemas."
  (:require [bitch-tracker.law.schemas :as schemas]
            [malli.core :as m]
            [cljs.test :refer-macros [deftest is testing]]))

(def sample-event
  {:schema "openplanner.event.v1"
   :schema_version 1
   :id "discord:g1:c1:m1"
   :ts "2026-06-16T00:00:00.000Z"
   :source "betterdiscord-openplanner"
   :kind "discord.message"
   :source_ref {:project "discord" :session "g1" :message "m1"}
   :text "hello"
   :meta {:author "alice"
          :author_id "u1"
          :author_username "alice"
          :author_global_name nil
          :bot false
          :tags ["discord" "message"]}
   :extra {:guild_id "g1" :channel_id "c1"}})

(deftest open-planner-event-id-validates
  (testing "event id must match discord guild/channel/message format"
    (is (m/validate schemas/OpenPlannerEventId "discord:g1:c1:m1"))
    (is (not (m/validate schemas/OpenPlannerEventId "not-an-id")))))

(deftest attachment-schema-validates
  (testing "attachment requires an id and accepts optional fields"
    (is (m/validate schemas/Attachment {:id "a1" :filename "x.png"}))
    (is (not (m/validate schemas/Attachment {:filename "x.png"})))))

(deftest event-meta-schema-validates
  (testing "event meta requires author, author_id, bot, and tags"
    (is (m/validate schemas/EventMeta {:author "alice" :author_id "u1" :bot false :tags []}))
    (is (not (m/validate schemas/EventMeta {:author "alice"})))))

(deftest open-planner-event-schema-validates
  (testing "valid event passes and closed maps reject extra keys"
    (is (m/validate schemas/OpenPlannerEvent sample-event))
    (is (not (m/validate schemas/OpenPlannerEvent (assoc sample-event :extra-key true))))))

(deftest label-payload-schema-validates
  (testing "label payload requires user, message, reactor, and guild ids"
    (is (m/validate schemas/LabelPayload {:user-id "u1" :message-id "m1" :reactor-id "r1" :message nil :channel nil :guild-id "g1"}))
    (is (not (m/validate schemas/LabelPayload {:user-id "u1"})))))

(deftest bot-config-schema-validates
  (testing "bot config requires all typed keys and is closed"
    (let [config {:token "t" :app-id "a" :bot-user-id "b" :bot-username "B"
                  :plugin-status-channel-id "c" :slapper-of-bitches-role-id "r"
                  :socket-port 7878 :public-base-url "" :openplanner-base-url "http://localhost"
                  :openplanner-api-key "" :openplanner-project "discord"
                  :tracker-channel-id "t" :watch-channel-id "w" :label-threshold 3
                  :flush-every-ms 1500 :semantic-scan-every-ms 30000
                  :max-batch-size 25 :max-persisted-events 500 :backfill-days 7}]
      (is (m/validate schemas/BotConfig config))
      (is (not (m/validate schemas/BotConfig (dissoc config :token)))))))

(deftest watch-entry-schema-validates
  (testing "watch entry requires a label and a compiled regex object"
    (is (m/validate schemas/WatchEntry {:label "badword" :pattern #"badword"}))
    (is (not (m/validate schemas/WatchEntry {:label "badword" :pattern "badword"})))))

(deftest socket-message-schema-validates
  (testing "socket messages are discriminated by :op"
    (is (m/validate schemas/SocketMessage {:op :event :event sample-event}))
    (is (m/validate schemas/SocketMessage {:op :label-added :payload {:user-id "u1" :message-id "m1" :reactor-id "r1" :message nil :channel nil :guild-id "g1"}}))
    (is (m/validate schemas/SocketMessage {:op :backfill :ts "2026-06-16T00:00:00.000Z"}))
    (is (not (m/validate schemas/SocketMessage {:op :unknown})))))
