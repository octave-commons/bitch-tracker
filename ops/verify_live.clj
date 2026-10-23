(ns verify-live
  "Read-only verification of the installed release and its admitted payloads."
  (:require [bitch-tracker.infra.federation :as agent]))

(let [state (agent/read-edn (str agent/state-root "/status.edn") {})
      profile (agent/read-edn agent/config-path {})
      release (agent/load-release (:channel profile))]
  (assert (= (:generation release) (get-in state [:installed :generation]))
          "Installed generation has not converged to the local channel")
  (doseq [[artifact expected] (get-in state [:installed :files])]
    (assert (= expected (agent/file-digest (agent/target-path agent/home artifact)))
            (str "Installed file mismatch: " artifact)))
  (prn {:host (:host state) :generation (get-in state [:installed :generation])
        :verified-files (count (get-in state [:installed :files])) :attention (:attention state)}))
