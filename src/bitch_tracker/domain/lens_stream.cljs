(ns bitch-tracker.domain.lens-stream
  "Folds Knoxx /ws/stream envelopes for one run into relay actions.
  No I/O. Envelopes are {:channel :payload} maps with keyword keys.")

(defn initial-run
  "Returns the fold state for a run that has been started but not yet streamed."
  [run-id]
  {:run-id run-id :text "" :status :running})

(defn- payload-run-id [payload]
  (str (or (:run_id payload) (:runId payload) "")))

(defn- tool-name [payload]
  (str (or (:tool_name payload) (:toolName payload) (:name payload) (:tool payload) "tool")))

(defn step
  "Applies one envelope to the run. Returns {:run run' :actions [[:token s] [:event m] [:done s] [:error s]]}.
  Envelopes for other runs, other channels, or a finished run produce no actions."
  [run {:keys [channel payload]}]
  (if (or (not= :running (:status run))
          (not= (:run-id run) (payload-run-id payload)))
    {:run run :actions []}
    (case channel
      "tokens"
      (if (= "assistant_message" (:kind payload))
        (let [token (str (:token payload))]
          {:run (update run :text str token) :actions [[:token token]]})
        {:run run :actions []})

      "events"
      (case (:type payload)
        "run_completed" {:run (assoc run :status :completed) :actions [[:done (:text run)]]}
        "run_failed" (let [reason (str (or (:error payload) (:reason payload) "Knoxx run failed"))]
                       {:run (assoc run :status :failed :error reason) :actions [[:error reason]]})
        "tool_start" {:run run :actions [[:event {:type "tool_start" :tool (tool-name payload)}]]}
        "tool_end" {:run run :actions [[:event {:type "tool_end" :tool (tool-name payload)}]]}
        "abort_requested" {:run run :actions [[:event {:type "abort_requested"}]]}
        {:run run :actions []})

      {:run run :actions []})))
