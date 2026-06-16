(ns bitch-tracker.domain.policy
  "Compile-time domain constants and policy values.
  No I/O. No mutable state.")

(def plugin-name
  "Display name of the Discord plugin."
  "BitchTracker")

(def plugin-version
  "Semantic version of the Discord plugin."
  "1.0.0")

(def default-bot-url
  "Default HTTP URL for the local bot endpoint."
  "http://127.0.0.1:7878")

(def default-socket-port
  "Default TCP port for the bot socket server."
  7878)

;; Discord guilds the plugin tracks (populated at runtime from bot config)
(def guild-ids
  "Set of Discord guild IDs the plugin should track."
  #{})

;; Emoji name/IDs that constitute a label
(def label-emoji-names
  "Set of emoji names and IDs treated as message labels."
  #{"⬆️" "arrow_up"})

;; Number of labels before a moderation-watch toast fires
(def label-threshold
  "Number of label reactions required to trigger a watch alert."
  5)

;; Discord channel IDs for tracker / watch output
(def tracker-channel-id
  "Fallback Discord channel ID for tracker output."
  "0000000000000000")

(def watch-channel-id
  "Fallback Discord channel ID for watch alerts."
  "1111111111111111")

;; Bot default config values
(def default-flush-every-ms
  "Default interval between cache flushes to the bot, in milliseconds."
  5000)

(def default-max-batch-size
  "Default maximum number of events in a single batch flush."
  50)

(def default-max-persisted
  "Default maximum number of dedup entries to persist to disk."
  1000)
