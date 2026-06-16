(ns bitch-tracker.law.contracts-test
  "Tests for bitch-tracker.law.contracts."
  (:require [bitch-tracker.law.contracts :as contracts]
            [cljs.test :refer-macros [deftest is testing]]))

(deftest valid-open-planner-event!-accepts-valid-event
  (testing "a valid event does not throw"
    (is (nil? (contracts/valid-open-planner-event!
               {:schema "openplanner.event.v1"
                :schema_version 1
                :id "discord:g1:c1:m1"
                :ts "2026-06-16T00:00:00.000Z"
                :source "test"
                :kind "discord.message"
                :source_ref {:project "p" :session "g1" :message "m1"}
                :text "hello"
                :meta {:author "alice" :author_id "u1" :bot false :tags []}
                :extra {}})))))

(deftest valid-open-planner-event!-throws-with-humanized-errors
  (testing "an invalid event throws an ex-info containing humanized errors"
    (try
      (contracts/valid-open-planner-event! {:id "bad-id"})
      (is false "expected exception")
      (catch :default e
        (is (= :bitch-tracker.validation/error (:type (ex-data e))))
        (is (some? (:errors (ex-data e))))))))

(deftest valid-label-payload!-enforces-shape
  (testing "a missing key throws with humanized errors"
    (try
      (contracts/valid-label-payload! {:user-id "u1"})
      (is false "expected exception")
      (catch :default e
        (is (= :bitch-tracker.validation/error (:type (ex-data e))))))))

(deftest valid-bot-config!-enforces-closed-map
  (testing "an extra key in config throws"
    (try
      (contracts/valid-bot-config! {:token "t" :extra-key true})
      (is false "expected exception")
      (catch :default e
        (is (= :bitch-tracker.validation/error (:type (ex-data e))))))))

(deftest valid-socket-message!-dispatches-on-op
  (testing "an unknown op throws"
    (try
      (contracts/valid-socket-message! {:op :unknown})
      (is false "expected exception")
      (catch :default e
        (is (= :bitch-tracker.validation/error (:type (ex-data e))))))))
