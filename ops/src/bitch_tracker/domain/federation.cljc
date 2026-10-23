(ns bitch-tracker.domain.federation
  "Release admissibility and install decisions. Pure data; no process or filesystem access.")

(def targets
  "Complete allowlist of artifacts and their account-relative destinations."
  {"BitchTracker.plugin.js" ".config/BetterDiscord/plugins/BitchTracker.plugin.js"
   "KnoxxLens.plugin.js" ".config/BetterDiscord/plugins/KnoxxLens.plugin.js"
   "UXXDiscord.plugin.js" ".config/BetterDiscord/plugins/UXXDiscord.plugin.js"
   "betterdiscord.asar" ".config/BetterDiscord/data/betterdiscord.asar"
   "security-policy.conf" ".local/share/bitch-tracker/security-policy.conf"
   "federation-domain.cljc" ".local/lib/bitch-tracker/bitch_tracker/domain/federation.cljc"
   "federation-agent.clj" ".local/lib/bitch-tracker/bitch_tracker/infra/federation.clj"})

(defn manifest-problems
  "Return release-shape violations without interpreting any executable data."
  [manifest]
  (let [files (:files manifest)]
    (cond-> []
      (not= 1 (:schema manifest)) (conj :unsupported-schema)
      (not (and (string? (:generation manifest))
                (re-matches #"[a-f0-9]{64}" (:generation manifest)))) (conj :invalid-generation)
      (not= (set (keys targets)) (set (keys files))) (conj :unexpected-files)
      (not (every? #(and (string? %) (re-matches #"[a-f0-9]{64}" %)) (vals files))) (conj :invalid-digests))))

(defn install-plan
  "Unknown/manual local changes hold the entire release. Enrollment explicitly
  adopts existing files, after backing them up; it is never a scheduled action."
  [manifest installed observed enroll?]
  (let [changes (->> (:files manifest)
                     (keep (fn [[artifact digest]]
                             (when (not= digest (get observed artifact)) artifact)))
                     sort vec)
        conflicts (if enroll? []
                      (filterv #(not= (get-in installed [:files %]) (get observed %)) changes))]
    {:changes changes :conflicts conflicts
     :action (cond (seq (manifest-problems manifest)) :reject
                   (seq conflicts) :hold
                   (empty? changes) :current
                   :else :install)}))

(defn notification-due?
  "True for new conditions or once a day while an action remains outstanding."
  [previous now-ms]
  (or (nil? previous) (>= (- now-ms previous) (* 24 60 60 1000))))
