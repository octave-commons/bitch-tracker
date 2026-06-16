(ns bitch-tracker.domain.dedup-test
  "Tests for bitch-tracker.domain.dedup."
  (:require [bitch-tracker.domain.dedup :as dedup]
            [cljs.test :refer-macros [deftest is testing]]))

(deftest empty-store-has-no-seen-ids
  (testing "a fresh store reports nothing as seen"
    (let [store (dedup/empty-store)]
      (is (not (dedup/seen? store "id" 1000 0))))))

(deftest add-marks-id-seen
  (testing "after adding an id it is seen until its TTL elapses"
    (let [store (-> (dedup/empty-store)
                    (dedup/add "id" 0))]
      (is (dedup/seen? store "id" 1000 0))
      (is (dedup/seen? store "id" 1000 50)))))

(deftest seen?-false-after-ttl
  (testing "an id is not seen once the supplied TTL has passed"
    (let [store (-> (dedup/empty-store)
                    (dedup/add "id" 0))]
      (is (not (dedup/seen? store "id" 1000 1001))))))

(deftest evict-expired-removes-old-entries
  (testing "evict-expired drops entries past their TTL while keeping fresh ones"
    (let [store (-> (dedup/empty-store)
                    (dedup/add "old" 0)
                    (dedup/add "new" 5000))
          evicted (dedup/evict-expired store 86400001)]
      (is (not (dedup/seen? evicted "old" 86400000 86400001)))
      (is (dedup/seen? evicted "new" 86400000 86400001)))))

(deftest evict-expired-keeps-unexpired-entries
  (testing "entries that have not reached their TTL remain after eviction"
    (let [store (-> (dedup/empty-store)
                    (dedup/add "id" 0))
          evicted (dedup/evict-expired store 1)]
      (is (dedup/seen? evicted "id" 86400000 1)))))

(deftest add-overwrites-existing-id
  (testing "adding the same id again refreshes its timestamp"
    (let [store (-> (dedup/empty-store)
                    (dedup/add "id" 0)
                    (dedup/add "id" 1000))]
      (is (dedup/seen? store "id" 1000 1500))
      (is (not (dedup/seen? store "id" 1000 2001))))))
