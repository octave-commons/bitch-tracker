(ns federation-test
  (:require [babashka.fs :as fs]
            [bitch-tracker.domain.federation :as policy]
            [bitch-tracker.infra.federation :as agent]
            [clojure.test :as t]))

(set! *warn-on-reflection* true)

(def old-hash "Baseline payload digest." "0000000000000000000000000000000000000000000000000000000000000000")
(def new-hash "Updated payload digest." "1111111111111111111111111111111111111111111111111111111111111111")
(def old-files "Known installed file map." (zipmap (keys policy/targets) (repeat old-hash)))
(def new-files "Candidate file map." (assoc old-files "KnoxxLens.plugin.js" new-hash))
(def manifest "Admissible candidate manifest." {:schema 1 :generation (agent/generation new-files) :files new-files})

(t/deftest manifest-rejects-executable-path-injection
  (t/is (= [] (policy/manifest-problems manifest)))
  (t/is (seq (policy/manifest-problems (update manifest :files assoc "../../outside" new-hash))))
  (t/is (seq (policy/manifest-problems (assoc manifest :generation "../../outside")))))

(t/deftest local-modifications-hold-the-whole-generation
  (t/is (= :install (:action (policy/install-plan manifest {:files old-files} old-files false))))
  (let [modified (assoc old-files "KnoxxLens.plugin.js" "manual")
        plan (policy/install-plan manifest {:files old-files} modified false)]
    (t/is (= :hold (:action plan)))
    (t/is (= ["KnoxxLens.plugin.js"] (:conflicts plan)))
    (t/is (= :install (:action (policy/install-plan manifest {:files old-files} modified true)))))
  (t/is (= :current (:action (policy/install-plan manifest manifest new-files false)))))

(t/deftest install-integrity-and-backup-boundary
  (let [root (fs/create-temp-dir {:prefix "suite-generation-test-"})
        channel (fs/path root "channel")
        files (into {} (for [artifact (keys policy/targets)]
                         [artifact (agent/digest (.getBytes (str "released-" artifact) "UTF-8"))]))
        generation (agent/generation files)
        release {:schema 1 :generation generation :files files}]
    (doseq [artifact (keys policy/targets)]
      (let [dest (fs/path channel "releases" generation artifact)
            target (agent/target-path root artifact)]
        (fs/create-dirs (fs/parent dest))
        (spit (str dest) (str "released-" artifact))
        (fs/create-dirs (fs/parent target))
        (spit (str target) (str "prior-" artifact))))
    (agent/atomic-text! (str channel "/stable.edn") (pr-str release))
    (t/is (= :hold (get-in (agent/install-release! root channel nil false) [:plan :action])))
    (t/is (= "prior-KnoxxLens.plugin.js" (slurp (str (agent/target-path root "KnoxxLens.plugin.js")))))
    ;; Corrupt payload: no managed file can change.
    (spit (str (fs/path channel "releases" generation "KnoxxLens.plugin.js")) "corrupt")
    (t/is (thrown? clojure.lang.ExceptionInfo (agent/install-release! root channel nil true)))
    (t/is (= "prior-KnoxxLens.plugin.js" (slurp (str (agent/target-path root "KnoxxLens.plugin.js")))))
    (spit (str (fs/path channel "releases" generation "KnoxxLens.plugin.js")) "released-KnoxxLens.plugin.js")
    (let [result (agent/install-release! root channel nil true)]
      (t/is (= :install (get-in result [:plan :action])))
      (t/is (= "released-KnoxxLens.plugin.js" (slurp (str (agent/target-path root "KnoxxLens.plugin.js")))))
      (t/is (= "prior-KnoxxLens.plugin.js" (slurp (str (fs/path (:backup result) "KnoxxLens.plugin.js")))))
      (t/is (= :current (get-in (agent/install-release! root channel (:installed result) false) [:plan :action])))
      ;; A second rename fails mid-upgrade: all already-installed files roll back.
      (let [next-files (into {} (for [artifact (keys policy/targets)]
                                 [artifact (agent/digest (.getBytes (str "next-" artifact) "UTF-8"))]))
            next-id (agent/generation next-files)
            real-move fs/move
            moves (atom 0)]
        (doseq [artifact (keys policy/targets)]
          (let [payload (fs/path channel "releases" next-id artifact)]
            (fs/create-dirs (fs/parent payload))
            (spit (str payload) (str "next-" artifact))))
        (agent/atomic-text! (str channel "/stable.edn")
                            (pr-str {:schema 1 :generation next-id :files next-files}))
        (with-redefs [fs/move (fn [& args]
                               (when (= 2 (swap! moves inc)) (throw (ex-info "simulated write failure" {})))
                               (apply real-move args))]
          (t/is (thrown? clojure.lang.ExceptionInfo
                         (agent/install-release! root channel (:installed result) false))))
        (t/is (= files (into {} (for [artifact (keys policy/targets)]
                                 [artifact (agent/file-digest (agent/target-path root artifact))]))))))
    (spit (str (fs/path root "unmanaged")) "keep")
    (t/is (= "keep" (slurp (str (fs/path root "unmanaged")))))
    (println "Synthetic verification artifacts retained at" (str root))))

(t/deftest daily-notification-cadence
  (t/is (policy/notification-due? nil 10))
  (t/is (not (policy/notification-due? 10 20)))
  (t/is (policy/notification-due? 10 (+ 10 (* 24 60 60 1000)))))

(let [result (t/run-tests)]
  (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
