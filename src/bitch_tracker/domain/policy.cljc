(ns bitch-tracker.domain.policy
  "Compile-time domain constants and policy values.
  No I/O. No mutable state.")

(def plugin-name    "BitchTracker")
(def plugin-version "1.0.0")

(def default-bot-url     "http://127.0.0.1:7878")
(def default-socket-port 7878)

;; Discord guilds the plugin tracks
(def guild-ids #{"1234567890" "0987654321"})

;; Emoji name/IDs that constitute a label
(def label-emoji-names #{"⬆️" "arrow_up"})

;; Number of labels before a moderation-watch toast fires
(def label-threshold 5)

;; Discord channel IDs for tracker / watch output
(def tracker-channel-id "0000000000000000")
(def watch-channel-id   "1111111111111111")

;; Bot default config values
(def default-flush-every-ms    5000)
(def default-max-batch-size    50)
(def default-max-persisted     1000)
