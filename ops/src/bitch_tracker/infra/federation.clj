(ns bitch-tracker.infra.federation
  "Workstation suite release adapter. Uses native SSH/rsync, systemd and APT;
  does not merge source checkouts or decide provider/model policy."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [bitch-tracker.domain.federation :as domain]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.nio.channels FileChannel]
           [java.nio.file StandardOpenOption]))

(set! *warn-on-reflection* true)

(def home
  "Current account home; managed writes cannot escape it." (System/getProperty "user.home"))
(def state-root
  "Per-host update state directory." (str home "/.local/state/bitch-tracker"))
(def data-root
  "Per-host suite artifact directory." (str home "/.local/share/bitch-tracker"))
(def config-path
  "Device-specific federation profile." (str home "/.config/bitch-tracker/federation.edn"))

(defn read-edn
  "Read EDN data, or return fallback for an absent file." [path fallback]
  (if (fs/exists? path) (edn/read-string (slurp (str path))) fallback))

(defn atomic-text!
  "Replace a text file through an atomic same-directory rename." [path value]
  (fs/create-dirs (fs/parent path))
  (let [tmp (str path "." (random-uuid) ".tmp")]
    (spit tmp value)
    (fs/move tmp path {:replace-existing true :atomic-move true})))

(defn digest
  "Compute a SHA-256 hex digest." [content-bytes]
  (format "%064x" (BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256") content-bytes))))

(defn file-digest
  "Hash a file, returning nil when it does not exist." [path]
  (when (fs/exists? path) (digest (fs/read-all-bytes path))))

(defn generation
  "Derive a generation from the canonical payload digest map." [files] (digest (.getBytes (pr-str (into (sorted-map) files)) "UTF-8")))

(defn run-command!
  "Run an external adapter and throw on nonzero exit." [args]
  (let [{:keys [exit out err]} (apply process/sh args)]
    (when-not (zero? exit)
      (throw (ex-info (str "Command failed: " (first args) " (" exit ")")
                      {:command (first args) :detail (subs (str err) 0 (min 1200 (count (str err))))})))
    out))

(defn target-path
  "Resolve a whitelisted managed artifact beneath the account home." [root artifact]
  (let [path (fs/path root (get domain/targets artifact))
        resolved (str (fs/canonicalize path))
        allowed (str (fs/canonicalize root) "/")]
    (when-not (str/starts-with? resolved allowed)
      (throw (ex-info "Managed target escapes home" {:file artifact})))
    (when (fs/sym-link? path)
      (throw (ex-info "Managed target is a symlink; adoption needs review" {:file artifact})))
    path))

(defn load-release
  "Validate manifest, generation identity and all payload hashes." [channel]
  (let [manifest (read-edn (str channel "/stable.edn") nil)
        problems (domain/manifest-problems manifest)]
    (when (seq problems) (throw (ex-info "Invalid release manifest" {:problems problems})))
    (when-not (= (:generation manifest) (generation (:files manifest)))
      (throw (ex-info "Generation digest mismatch" {})))
    (doseq [[artifact expected] (:files manifest)]
      (when-not (= expected (file-digest (fs/path channel "releases" (:generation manifest) artifact)))
        (throw (ex-info "Release payload digest mismatch; update held" {:file artifact}))))
    manifest))

(defn install-release!
  "Back up and atomically replace admitted artifacts, restoring on write failure." [root channel installed enroll?]
  (let [manifest (load-release channel)
        observed (into {} (for [artifact (keys domain/targets)] [artifact (file-digest (target-path root artifact))]))
        plan (domain/install-plan manifest installed observed enroll?)
        backup (fs/path root ".local/share/bitch-tracker/backups" (str "generation-" (:generation manifest) "-" (System/currentTimeMillis)))
        written (atom [])]
    (if (not= :install (:action plan))
      {:plan plan :installed (if (= :current (:action plan)) manifest installed)}
      (do
        ;; Finish all backups before the first managed-file mutation.
        (doseq [artifact (:changes plan)]
          (let [target (target-path root artifact)]
            (when (fs/exists? target)
              (fs/create-dirs backup)
              (fs/copy target (fs/path backup artifact)))))
        (try
          (doseq [artifact (:changes plan)]
            (let [target (target-path root artifact)
                  tmp (str target "." (random-uuid) ".tmp")]
              (fs/create-dirs (fs/parent target))
              (fs/copy (fs/path channel "releases" (:generation manifest) artifact) tmp)
              (when-not (= (get-in manifest [:files artifact]) (file-digest tmp))
                (throw (ex-info "Payload changed while staging; installation rolled back" {:file artifact})))
              (fs/move tmp target {:replace-existing true :atomic-move true})
              (swap! written conj artifact)))
          {:plan plan :installed manifest :backup (str backup)}
          (catch Exception error
            (doseq [artifact @written]
              (let [target (target-path root artifact) saved (fs/path backup artifact)]
                (if (fs/exists? saved)
                  (fs/copy saved target {:replace-existing true})
                  (fs/delete target))))
            (throw error)))))))

(defn package-attention
  "Read native Ubuntu update and package-repair status." []
  (let [result (process/sh "/usr/lib/update-notifier/apt-check")
        counts (re-find #"(\d+);(\d+)" (str (:out result) (:err result)))
        apt-result (str/trim (run-command! ["systemctl" "show" "apt-daily-upgrade.service" "-p" "Result" "--value"]))
        audit (str/trim (run-command! ["dpkg" "--audit"]))
        repair-state (str/trim (run-command! ["systemctl" "--user" "show" "betterdiscord-repair.service" "-p" "ActiveState" "--value"]))]
    (vec (concat
          (when (fs/exists? "/var/run/reboot-required")
            [{:kind :reboot-required :message "Security updates require a reboot. Restart the computer when convenient."}])
          (when (= "failed" repair-state)
            [{:kind :betterdiscord-repair-failed :message "BetterDiscord repair is failing. Inspect the betterdiscord-repair.service user journal; a Discord compatibility update may be needed."}])
          (when (and (seq apt-result) (not= "success" apt-result))
            [{:kind :security-update-failed :message "Ubuntu security updates failed. Inspect apt-daily-upgrade.service in the system journal."}])
          (when (and (fs/exists? (str data-root "/security-policy.conf"))
                     (not= (file-digest (str data-root "/security-policy.conf"))
                           (file-digest "/etc/apt/apt.conf.d/52federated-suite-security")))
            [{:kind :security-policy-update :message "An updated security policy needs administrator installation. Review ~/.local/share/bitch-tracker/security-policy.conf before installing it into /etc/apt/apt.conf.d/."}])
          (when (seq audit)
            [{:kind :package-repair-required :message "Ubuntu reports unfinished package configuration. Run dpkg --audit and review the repair."}])
          (if counts
            (let [other (- (parse-long (nth counts 1)) (parse-long (nth counts 2)))]
              (when (pos? other)
                [{:kind :other-package-updates :message (str other " non-security package updates are available. Review those not handled by existing updaters; security updates remain automatic.")}]))
            [{:kind :package-status-unavailable :message "Could not inspect Ubuntu update status; inspect update-notifier apt-check."}])))))

(defn notify!
  "Notify about outstanding human actions at most once a day per condition." [state attention]
  (let [now (System/currentTimeMillis)
        active-keys (set (map :kind attention))
        prior (select-keys (:notified state) active-keys)]
    (reduce
     (fn [notified {:keys [kind message]}]
       (if-not (domain/notification-due? (get notified kind) now)
         notified
         (let [result (process/sh "notify-send" "--app-name=Federated Suite" "System needs your attention" message)]
           (if (zero? (:exit result)) (assoc notified kind now) notified))))
     prior attention)))

(defn save-status!
  "Persist machine-readable installed state and human attention requirements." [state attention]
  (let [state (assoc state :host (str/trim (run-command! ["hostname"]))
                          :checked-at (str (java.time.Instant/now)) :attention attention)
        state (assoc state :notified (notify! state attention))]
    (atomic-text! (str state-root "/status.edn") (str (pr-str state) "\n"))
    state))

(defn discord-instance
  "Identify the current Discord session from its singleton lock." []
  (let [lock (fs/path home ".config/discord/SingletonLock")]
    (when (fs/sym-link? lock) (str (fs/read-link lock)))))

(defn observed-package-attention
  "Keep monitoring failures visible without blocking suite file recovery." []
  (try (package-attention)
       (catch Exception _ [{:kind :package-status-unavailable
                            :message "Package monitoring failed. Inspect the suite update journal."}])))

(defn check!
  "Apply the local release channel and inspect Ubuntu update requirements." [enroll?]
  (let [config (read-edn config-path nil)
        state (read-edn (str state-root "/status.edn") {})]
    (when-not config (throw (ex-info "Missing federation profile" {})))
    (try
      (let [{:keys [plan installed backup]} (install-release! home (:channel config) (:installed state) enroll?)
            hold? (= :hold (:action plan))
            attention (into (observed-package-attention)
                            (when hold? [{:kind :local-modifications
                                          :message (str "Suite update held to preserve local changes in " (str/join ", " (:conflicts plan)) ". Review before enrolling a new baseline.")}]))
            current-instance (discord-instance)
            restart-instance (if (and (= :install (:action plan))
                                      (some #{"betterdiscord.asar"} (:changes plan)))
                               current-instance
                               (:discord-restart-instance state))
            restart-instance (when (= restart-instance current-instance) restart-instance)
            attention (into attention
                            (when restart-instance
                              [{:kind :discord-restart :message "BetterDiscord was updated. Restart Discord when convenient to use the new framework."}]))]
        (save-status! (cond-> (assoc state :installed installed :suite-result plan :update-failures 0
                                         :discord-restart-instance restart-instance)
                        backup (assoc :last-backup backup)) attention))
      (catch Exception error
        (save-status! (update state :update-failures (fnil inc 0))
                      (conj (observed-package-attention) {:kind :suite-update-failed
                                      :message (str (.getMessage error) ". Files were preserved; inspect the suite update journal.")}))
        (throw error)))))

(defn sync!
  "Copy the current immutable generation to enrolled peers and collect status." []
  (let [config (read-edn config-path nil)
        channel (:channel config)
        manifest (load-release channel)]
    (doseq [{:keys [id ssh]} (:peers config)]
      (let [peer-file (str state-root "/peers/" (clojure.core/name id) ".edn")
            previous (read-edn peer-file {})]
        (try
          (let [transport "ssh -o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=5 -o ServerAliveInterval=5 -o ServerAliveCountMax=2"
                base (str ssh ":.local/share/bitch-tracker/channel/")]
            (run-command! ["timeout" "45" "ssh" "-o" "BatchMode=yes" "-o" "StrictHostKeyChecking=yes" "-o" "ConnectTimeout=5" ssh "mkdir -p .local/share/bitch-tracker/channel/releases"])
            ;; Payload first; atomic rsync rename of stable.edn makes it visible last.
            (run-command! ["timeout" "60" "rsync" "-a" "--protect-args" "-e" transport
                   (str channel "/releases/" (:generation manifest)) (str base "releases/")])
            (run-command! ["timeout" "30" "rsync" "-a" "--protect-args" "-e" transport
                   (str channel "/stable.edn") base])
            (run-command! ["timeout" "90" "ssh" "-o" "BatchMode=yes" "-o" "StrictHostKeyChecking=yes" "-o" "ConnectTimeout=5" ssh
                           "systemctl --user start bitch-tracker-update.service"])
            (let [observed (edn/read-string (run-command! ["timeout" "15" "ssh" "-o" "BatchMode=yes" "-o" "StrictHostKeyChecking=yes" "-o" "ConnectTimeout=5" ssh "cat .local/state/bitch-tracker/status.edn"]))
                  attention (mapv (fn [issue]
                                    {:kind (keyword (str (clojure.core/name id) "/" (clojure.core/name (:kind issue))))
                                     :message (str (clojure.core/name id) ": " (:message issue))})
                                  (:attention observed))]
              (atomic-text! peer-file (str (pr-str {:id id :synced-generation (:generation manifest)
                                                  :checked-at (str (java.time.Instant/now))
                                                  :notified (notify! previous attention)
                                                  :failures 0 :observed observed}) "\n"))))
          (catch Exception error
            (let [failures (inc (:failures previous 0))
                  state (assoc previous :id id :failures failures :error (.getMessage error))]
              (let [notified (when (>= failures 3)
                               (notify! previous [{:kind :peer-unreachable
                                                   :message (str "Cannot update " (clojure.core/name id) ". Check connectivity or SSH approval; installed versions are preserved.")}]))]
                (atomic-text! peer-file (str (pr-str (assoc state :notified (or notified (:notified previous)))) "\n")))
              (binding [*out* *err*] (println "Peer sync deferred:" (clojure.core/name id) (.getMessage error))))))))))

(defn publish!
  "Validate distributables and atomically publish an immutable release generation." [root channel]
  (let [root (str (fs/absolutize root))
        inputs {"BitchTracker.plugin.js" (str root "/dist/BitchTracker.plugin.js")
                "KnoxxLens.plugin.js" (str root "/dist/KnoxxLens.plugin.js")
                "UXXDiscord.plugin.js" (str root "/dist/UXXDiscord.plugin.js")
                "betterdiscord.asar" (str home "/.config/BetterDiscord/data/betterdiscord.asar")
                "security-policy.conf" (str root "/ops/apt/52federated-suite-security")
                "federation-domain.cljc" (str root "/ops/src/bitch_tracker/domain/federation.cljc")
                "federation-agent.clj" (str root "/ops/src/bitch_tracker/infra/federation.clj")}
        files (into (sorted-map) (map (fn [[k path]] [k (file-digest path)])) inputs)]
    (when (some nil? (vals files)) (throw (ex-info "Missing release payload" {})))
    (doseq [artifact ["BitchTracker.plugin.js" "KnoxxLens.plugin.js" "UXXDiscord.plugin.js"]]
      (run-command! ["node" "--check" (get inputs artifact)]))
    (run-command! ["node" (str root "/scripts/verify-bd-export.mjs")])
    (run-command! ["bb" "-cp" (str root "/ops/src") (str root "/ops/test/federation_test.clj")])
    (let [manifest {:schema 1 :generation (generation files) :files files
                    :created-at (str (java.time.Instant/now))
                    :source-head (str/trim (run-command! ["git" "-C" root "rev-parse" "HEAD"]))
                    :source-diff-sha256 (digest (.getBytes (run-command! ["git" "-C" root "diff" "--binary" "HEAD"]) "UTF-8"))}
          dest (fs/path channel "releases" (:generation manifest))]
      (fs/create-dirs dest)
      (doseq [[artifact path] inputs]
        (let [target (fs/path dest artifact)]
          (if (fs/exists? target)
            (when-not (= (get files artifact) (file-digest target))
              (throw (ex-info "Immutable release already exists with different contents" {:file artifact})))
            (fs/copy path target))))
      (doseq [[artifact expected] files]
        (when-not (= expected (file-digest (fs/path dest artifact)))
          (throw (ex-info "Payload changed during publication" {:file artifact}))))
      (when-not (fs/exists? (fs/path dest "manifest.edn"))
        (atomic-text! (str (fs/path dest "manifest.edn")) (str (pr-str manifest) "\n")))
      ;; Re-publishing the same payload preserves its original immutable provenance.
      (atomic-text! (str channel "/stable.edn")
                    (str (pr-str (read-edn (fs/path dest "manifest.edn") manifest)) "\n"))
      (println "Published" (:generation manifest)))))

(defn -main
  "Dispatch a suite operation under a per-host exclusive lock." [& args]
  (fs/create-dirs state-root)
  (with-open [channel (FileChannel/open (.toPath (java.io.File. (str state-root "/update.lock")))
                                      (into-array StandardOpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE]))]
    ;; Closing the channel releases its lock; SCI does not expose FileLock.close.
    (if-not (.tryLock channel)
      (println "Another suite operation is running; deferred until the next tick")
      (case (first args)
      "check" (prn (select-keys (check! false) [:host :suite-result :attention]))
      "enroll" (prn (select-keys (check! true) [:host :suite-result :attention]))
      "sync" (sync!)
      "status" (prn (read-edn (str state-root "/status.edn") {}))
      "fleet" (prn {:local (select-keys (read-edn (str state-root "/status.edn") {}) [:host :checked-at :suite-result :attention])
                     :peers (mapv #(read-edn % {}) (fs/glob (str state-root "/peers") "*.edn"))})
      "publish" (publish! (or (second args) ".") (or (nth args 2 nil) (str data-root "/channel")))
      "attention" (let [state (read-edn (str state-root "/status.edn") {})]
                    (save-status! state
                                  (vec (distinct (conj (:attention state [])
                                                       {:kind :service-failed
                                                        :message (str "Service " (second args) " failed. Inspect its user journal.")})))))
      (throw (ex-info "Expected check, enroll, sync, status, publish or attention" {}))))))
