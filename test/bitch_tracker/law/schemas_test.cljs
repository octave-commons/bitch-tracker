(ns bitch-tracker.law.schemas-test
  "Tests for bitch-tracker.law.schemas.

  A passing test here is a validated fact: the schema accepts this shape and
  rejects that shape. Every schema exported from law.schemas gets at least one
  accept and one reject case."
  (:require [cljs.test :refer [deftest is testing]]
            [malli.core :as m]
            [bitch-tracker.law.schemas :as schemas]))

;; ---------------------------------------------------------------------------
;; OpenPlannerEventId
;; ---------------------------------------------------------------------------

(deftest open-planner-event-id-accepts-valid-id
  (testing "a properly-formed discord:<g>:<c>:<m> id is valid"
    (is (m/validate schemas/OpenPlannerEventId "discord:guild1:ch1:msg1"))))

(deftest open-planner-event-id-rejects-missing-segment
  (testing "a two-segment id is rejected"
    (is (not (m/validate schemas/OpenPlannerEventId "discord:guild1:ch1")))))

;; ---------------------------------------------------------------------------
;; Attachment
;; ---------------------------------------------------------------------------

(deftest attachment-accepts-minimal-map
  (testing "an Attachment with only :id is valid (all other keys optional)"
    (is (m/validate schemas/Attachment {:id "att1"}))))

(deftest attachment-rejects-missing-id
  (testing "an Attachment without :id is invalid"
    (is (not (m/validate schemas/Attachment {:filename "x.png"})))))

;; ---------------------------------------------------------------------------
;; EventMeta
;; ---------------------------------------------------------------------------

(deftest event-meta-accepts-valid-map
  (testing "a fully-specified EventMeta is valid"
    (is (m/validate schemas/EventMeta
                    {:author "Alice" :author_id "u1" :bot false :tags []}))))

(deftest event-meta-rejects-missing-author
  (testing "EventMeta without :author is invalid"
    (is (not (m/validate schemas/EventMeta {:author_id "u1" :bot false :tags []})))))

;; ---------------------------------------------------------------------------
;; OpenPlannerEvent
;; ---------------------------------------------------------------------------

(def valid-event
  {:schema "openplanner.event.v1"
   :schema_version 1
   :id "discord:g1:c1:m1"
   :ts "2026-06-16T00:00:00Z"
   :source "bitch-tracker"
   :kind "discord.message"
   :source_ref {:project "proj" :session "sess" :message "msg"}
   :text "hello"
   :meta {:author "Alice" :author_id "u1" :bot false :tags []}
   :extra {}})

(deftest open-planner-event-accepts-valid-event
  (testing "a fully-specified OpenPlannerEvent passes validation"
    (is (m/validate schemas/OpenPlannerEvent valid-event))))

(deftest open-planner-event-rejects-wrong-schema-version
  (testing "schema_version 2 is rejected (must be exactly 1)"
    (is (not (m/validate schemas/OpenPlannerEvent
                         (assoc valid-event :schema_version 2))))))

(deftest open-planner-event-rejects-extra-keys
  (testing "unknown extra top-level key is rejected (closed map)"
    (is (not (m/validate schemas/OpenPlannerEvent
                         (assoc valid-event :surprise "nope"))))))

;; ---------------------------------------------------------------------------
;; LabelPayload
;; ---------------------------------------------------------------------------

(def valid-label-payload
  {:user-id "u1"
   :message-id "m1"
   :reactor-id "r1"
   :message nil
   :channel nil
   :guild-id "g1"})

(deftest label-payload-accepts-valid-payload
  (testing "a fully-specified LabelPayload passes validation"
    (is (m/validate schemas/LabelPayload valid-label-payload))))

(deftest label-payload-rejects-missing-guild-id
  (testing "LabelPayload without :guild-id is invalid"
    (is (not (m/validate schemas/LabelPayload
                         (dissoc valid-label-payload :guild-id))))))

;; ---------------------------------------------------------------------------
;; WatchEntry
;; ---------------------------------------------------------------------------

(deftest watch-entry-accepts-valid-entry
  (testing "a WatchEntry with a compiled RegExp passes validation"
    (is (m/validate schemas/WatchEntry
                    {:label "hate" :pattern (js/RegExp. "hate" "i")}))))

(deftest watch-entry-rejects-string-pattern
  (testing "a WatchEntry with a raw string pattern is rejected"
    (is (not (m/validate schemas/WatchEntry {:label "hate" :pattern "hate"})))))

;; ---------------------------------------------------------------------------
;; SocketMessage (discriminated multi)
;; ---------------------------------------------------------------------------

(deftest socket-message-accepts-label-added
  (testing "a :label-added socket message passes validation"
    (is (m/validate schemas/SocketMessage
                    {:op :label-added :payload valid-label-payload}))))

(deftest socket-message-accepts-status
  (testing "a :status socket message passes validation"
    (is (m/validate schemas/SocketMessage
                    {:op :status :status "ok" :bot-user-id nil :ts nil}))))

(deftest socket-message-rejects-unknown-op
  (testing "an unknown :op keyword is rejected"
    (is (not (m/validate schemas/SocketMessage {:op :unknown-op})))))
