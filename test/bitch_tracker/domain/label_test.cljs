(ns bitch-tracker.domain.label-test
  "Tests for bitch-tracker.domain.label."
  (:require [bitch-tracker.domain.label :as label]
            [cljs.test :refer-macros [deftest is testing]]))

(deftest empty-state-is-empty
  (testing "fresh state holds no counts, messages, or labeled ids"
    (let [state (label/empty-state)]
      (is (= {} (:counts state)))
      (is (= {} (:messages state)))
      (is (= #{} (:labeled state))))))

(deftest add-increments-count
  (testing "adding a label increments the user's count and marks the message"
    (let [state (-> (label/empty-state)
                    (label/add "u1" "m1"))]
      (is (= 1 (label/count-for state "u1")))
      (is (label/labeled? state "m1")))))

(deftest add-is-idempotent-for-same-message
  (testing "duplicate labels for the same user/message do not double-count"
    (let [state (-> (label/empty-state)
                    (label/add "u1" "m1")
                    (label/add "u1" "m1"))]
      (is (= 1 (label/count-for state "u1")))
      (is (= #{"m1"} (get-in state [:messages "u1"]))))))

(deftest add-tracks-distinct-messages
  (testing "different messages from the same user each increase the count"
    (let [state (-> (label/empty-state)
                    (label/add "u1" "m1")
                    (label/add "u1" "m2"))]
      (is (= 2 (label/count-for state "u1")))
      (is (= #{"m1" "m2"} (get-in state [:messages "u1"]))))))

(deftest remove-label-decrements-count
  (testing "removing a label lowers the count and unmarks the message"
    (let [state (-> (label/empty-state)
                    (label/add "u1" "m1")
                    (label/remove-label "u1" "m1"))]
      (is (= 0 (label/count-for state "u1")))
      (is (not (label/labeled? state "m1"))))))

(deftest remove-label-floors-at-zero
  (testing "removing a non-existent label cannot produce a negative count"
    (let [state (-> (label/empty-state)
                    (label/remove-label "u1" "m1"))]
      (is (= 0 (label/count-for state "u1"))))))

(deftest count-for-unknown-user-is-zero
  (testing "users never seen have a count of zero"
    (is (= 0 (label/count-for (label/empty-state) "unknown")))))

(deftest labeled?-returns-false-for-unknown-message
  (testing "messages never labeled are not labeled"
    (is (not (label/labeled? (label/empty-state) "unknown")))))

(deftest threshold-crossed?-true-when-below
  (testing "threshold-crossed? is true while the count is strictly below threshold"
    (let [state (-> (label/empty-state)
                    (label/add "u1" "m1"))]
      (is (label/threshold-crossed? state "u1" 3)))))

(deftest threshold-crossed?-false-when-at-or-above
  (testing "threshold-crossed? is false once the count reaches threshold"
    (let [state (-> (label/empty-state)
                    (label/add "u1" "m1")
                    (label/add "u1" "m2")
                    (label/add "u1" "m3"))]
      (is (not (label/threshold-crossed? state "u1" 3))))))
