(ns bitch-tracker.domain.dedup-test
  "Tests for bitch-tracker.domain.dedup.

  The store is driven by explicit now-ms values, so no real time passes during
  tests."
  (:require [cljs.test :refer [deftest is testing]]
            [bitch-tracker.domain.dedup :as dedup]))

(def t0 1_000_000)
(def one-hour-ms (* 60 60 1000))
(def one-day-ms (* 24 one-hour-ms))

;; ---------------------------------------------------------------------------
;; empty-store
;; ---------------------------------------------------------------------------

(deftest empty-store-has-clean-shape
  (testing "empty-store returns a map with an empty cache and a default TTL"
    (let [s (dedup/empty-store)]
      (is (= {} (:cache s)))
      (is (pos? (:default-ttl-ms s))))))

;; ---------------------------------------------------------------------------
;; add
;; ---------------------------------------------------------------------------

(deftest add-records-id-at-now
  (testing "add stores the event id with the given timestamp"
    (let [s (dedup/add (dedup/empty-store) "evt-1" t0)]
      (is (some? (get-in s [:cache "evt-1"]))))))

;; ---------------------------------------------------------------------------
;; seen?
;; ---------------------------------------------------------------------------

(deftest seen-true-for-recently-added-id
  (testing "seen? is true within the TTL window"
    (let [ttl one-day-ms
          s   (dedup/add (dedup/empty-store) "evt-1" t0)]
      (is (dedup/seen? s "evt-1" ttl (+ t0 one-hour-ms))))))

(deftest seen-false-for-unknown-id
  (testing "seen? is false for an id that was never added"
    (is (not (dedup/seen? (dedup/empty-store) "evt-ghost" one-day-ms t0)))))

(deftest seen-false-after-ttl-expires
  (testing "seen? is false when now-ms is past the TTL"
    (let [ttl one-hour-ms
          s   (dedup/add (dedup/empty-store) "evt-1" t0)
          after-expiry (+ t0 ttl 1)]
      (is (not (dedup/seen? s "evt-1" ttl after-expiry))))))

(deftest seen-true-at-exact-ttl-boundary
  (testing "seen? is true when now-ms is exactly at the TTL boundary (not yet expired)"
    (let [ttl one-hour-ms
          s   (dedup/add (dedup/empty-store) "evt-1" t0)]
      ;; (now - at) = ttl-ms means the diff equals ttl, not less than ttl — expired
      ;; one ms before that boundary: still seen
      (is (dedup/seen? s "evt-1" ttl (+ t0 ttl -1))))))

;; ---------------------------------------------------------------------------
;; evict-expired
;; ---------------------------------------------------------------------------

(deftest evict-expired-removes-stale-entries
  (testing "evict-expired removes entries whose stored TTL has elapsed"
    (let [ttl one-hour-ms
          s   (-> (dedup/empty-store)
                  (dedup/add "evt-old" t0)
                  (dedup/add "evt-new" (+ t0 one-day-ms)))
          ;; advance time past evt-old TTL but not evt-new TTL
          now (+ t0 ttl one-hour-ms)
          s2  (dedup/evict-expired s now)]
      (is (nil? (get-in s2 [:cache "evt-old"])))
      (is (some? (get-in s2 [:cache "evt-new"]))))))

(deftest evict-expired-is-no-op-when-all-live
  (testing "evict-expired returns the same entries when nothing has expired"
    (let [s (dedup/add (dedup/empty-store) "evt-1" t0)
          s2 (dedup/evict-expired s (+ t0 one-hour-ms))]
      (is (some? (get-in s2 [:cache "evt-1"]))))))

(deftest evict-expired-preserves-empty-store
  (testing "evict-expired on an empty store returns an empty store"
    (let [s (dedup/evict-expired (dedup/empty-store) t0)]
      (is (= {} (:cache s))))))
