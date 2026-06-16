(ns bitch-tracker.bot.main
  (:require [bitch-tracker.bot.config :as cfg]
            [bitch-tracker.bot.dedup :as dedup]
            [bitch-tracker.bot.discord :as discord]
            [bitch-tracker.bot.openplanner :as op]
            [bitch-tracker.bot.socket :as socket]))

(def state
  "Atom holding the running bot runtime state."
  (atom nil))

(defn- persist-dedup! []
  (when-let [{:keys [dedup-state]} @state]
    (dedup/persist! dedup-state (dedup/persist-path))))

(defn ^:async start!
  "Bootstraps and starts the BitchTracker bot server."
  []
  (js/console.log "[bot] Starting BitchTracker bot server...")
  (try
    (let [config (cfg/load-bot-config)
          ^js socket-state (socket/make-state)
          ^js op-state (op/make-state config)
          ^js dedup-state (dedup/make-state)
          ^js discord-client (await (discord/create-client (:token config)))]
      (dedup/load! dedup-state (dedup/persist-path))
      (set! (.-discord_client socket-state) discord-client)
      (set! (.-op_state socket-state) op-state)
      (set! (.-dedup_state socket-state) dedup-state)
      (op/start-timers! op-state config)
      (socket/start! socket-state config)
      (socket/notify-bot-online! socket-state config)
      (reset! state {:socket-state socket-state
                     :op-state op-state
                     :dedup-state dedup-state
                     :discord-client discord-client
                     :config config})
      (js/console.log "[bot] BitchTracker bot server started")
      (js/console.log "[bot] Socket.io port:" (:socket-port config))
      (js/console.log "[bot] Bot user ID:" (:bot-user-id config))
      (js/console.log "[bot] Dedup cache:" (.-size (.-cache dedup-state)) "entries"))
    (catch :default err
      (js/console.error "[bot] Failed to start:" (.-message err))
      (js/process.exit 1))))

(defn stop!
  "Persists dedup state and shuts down the bot."
  []
  (persist-dedup!)
  (when-let [{:keys [socket-state op-state discord-client]} @state]
    (js/console.log "[bot] Shutting down...")
    (socket/stop! socket-state)
    (op/stop-timers! op-state)
    (when-let [^js dc discord-client] (.destroy dc))
    (reset! state nil)
    (js/console.log "[bot] Stopped.")))

(defn -main
  "Entry point: starts the bot and registers shutdown hooks."
  [& _args]
  (start!)
  (js/process.on "SIGINT" (fn [] (stop!) (js/process.exit 0)))
  (js/process.on "SIGTERM" (fn [] (stop!) (js/process.exit 0))))

(defn main!
  "Convenience wrapper around -main."
  []
  (-main))
