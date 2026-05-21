(ns bitch-tracker.plugin-test
  (:require [bitch-tracker.plugin :as plugin]
            [cljs.test :refer-macros [deftest is testing]]))

(def test-meta
  #js {:name "BitchTracker"
       :version "0.0.1"
       :description "Test metadata"})

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
      (.stop instance)
      (is (= false (aget state "started")))
      (is (string? (aget state "stoppedAt"))))))
