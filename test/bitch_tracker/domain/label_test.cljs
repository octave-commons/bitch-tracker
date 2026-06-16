(ns bitch-tracker.domain.label-test
  "Tests for bitch-tracker.domain.label.

  Treats each public function as an epistemic contract: a passing test is a
  validated fact about pure label-state transitions."
  (:require [cljs.test :refer [deftest is testing]]
            [bitch-tracker.domain.label :as label]))

;; ---------------------------------------------------------------------------
;; empty-state
;; ---------------------------------------------------------------------------

(deftest empty-state-has-clean-shape
  (testing "empty-state returns a map with zero counts, messages, and labeled"
    (let [s (label/empty-state)]
      (is (= {} (:counts s)))
      (is (= {} (:messages s)))
      (is (= #{} (:labeled s))))))

;; ---------------------------------------------------------------------------
;; add
;; ---------------------------------------------------------------------------

(deftest add-increments-count-for-new-message
  (testing "adding a novel message-id increments the user's count"
    (let [s (-> (label/empty-state)
                (label/add "u1" "m1"))]
      (is (= 1 (label/count-for s "u1")))
      (is (label/labeled? s "m1")))))

(deftest add-is-idempotent-for-same-user-and-message
  (testing "adding the same message-id twice for the same user is a no-op"
    (let [s (-> (label/empty-state)
                (label/add "u1" "m1")
                (label/add "u1" "m1"))]
      (is (= 1 (label/count-for s "u1"))))))

(deftest add-allows-different-users-to-label-same-message
  (testing "two users can each label the same message; counts are independent"
    (let [s (-> (label/empty-state)
                (label/add "u1" "m1")
                (label/add "u2" "m1"))]
      (is (= 1 (label/count-for s "u1")))
      (is (= 1 (label/count-for s "u2")))
      (is (label/labeled? s "m1")))))

(deftest add-tracks-multiple-messages-per-user
  (testing "a single user can label many distinct messages"
    (let [s (-> (label/empty-state)
                (label/add "u1" "m1")
                (label/add "u1" "m2")
                (label/add "u1" "m3"))]
      (is (= 3 (label/count-for s "u1"))))))

;; ---------------------------------------------------------------------------
;; remove-label
;; ---------------------------------------------------------------------------

(deftest remove-label-decrements-count
  (testing "removing a labeled message decrements the count by one"
    (let [s (-> (label/empty-state)
                (label/add "u1" "m1")
                (label/remove-label "u1" "m1"))]
      (is (= 0 (label/count-for s "u1")))
      (is (not (label/labeled? s "m1"))))))

(deftest remove-label-is-idempotent-for-absent-entry
  (testing "removing a message the user never labeled is a no-op"
    (let [s0 (label/empty-state)
          s1 (label/remove-label s0 "u1" "m-ghost")]
      (is (= s0 s1)))))

(deftest remove-label-floors-count-at-zero
  (testing "count never goes below zero even with repeated removes"
    (let [s (-> (label/empty-state)
                (label/add "u1" "m1")
                (label/remove-label "u1" "m1")
                (label/remove-label "u1" "m1"))]
      (is (>= (label/count-for s "u1") 0)))))

(deftest remove-label-cleans-up-messages-entry-when-empty
  (testing "when a user's message set becomes empty, the user key is pruned"
    (let [s (-> (label/empty-state)
                (label/add "u1" "m1")
                (label/remove-label "u1" "m1"))]
      (is (nil? (get-in s [:messages "u1"]))))))

;; ---------------------------------------------------------------------------
;; count-for
;; ---------------------------------------------------------------------------

(deftest count-for-returns-zero-for-unknown-user
  (testing "count-for returns 0 for a user who has never labeled anything"
    (is (= 0 (label/count-for (label/empty-state) "nobody")))))

;; ---------------------------------------------------------------------------
;; labeled?
;; ---------------------------------------------------------------------------

(deftest labeled-returns-false-for-unlabeled-message
  (testing "labeled? is false when message has not been added"
    (is (not (label/labeled? (label/empty-state) "m-new")))))

;; ---------------------------------------------------------------------------
;; threshold-crossed?
;; ---------------------------------------------------------------------------

(deftest threshold-crossed-true-when-below-threshold
  (testing "threshold-crossed? is true when count is strictly below threshold"
    (let [s (-> (label/empty-state)
                (label/add "u1" "m1"))]
      (is (label/threshold-crossed? s "u1" 3)))))

(deftest threshold-crossed-false-when-at-or-above-threshold
  (testing "threshold-crossed? is false when count equals threshold"
    (let [s (-> (label/empty-state)
                (label/add "u1" "m1")
                (label/add "u1" "m2")
                (label/add "u1" "m3"))]
      (is (not (label/threshold-crossed? s "u1" 3))))))

(deftest threshold-crossed-false-for-zero-threshold
  (testing "threshold 0 means nothing can cross it; always false"
    (is (not (label/threshold-crossed? (label/empty-state) "u1" 0)))))
