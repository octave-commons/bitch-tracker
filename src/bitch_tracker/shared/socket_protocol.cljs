(ns bitch-tracker.shared.socket-protocol
  "Legacy socket.io event name constants.
  Prefer bitch-tracker.shape.protocol for new code.")

;; Socket.io event names for plugin ↔ bot communication
;;
;; Plugin → Bot:
;;   "event"           — Forward an OpenPlanner event (message or reaction)
;;   "label:added"     — A poodle/clown label was applied to a message
;;   "label:removed"   — A label was removed from a message
;;   "backfill"        — Request a backfill
;;   "config:request"  — Request current bot config
;;
;; Bot → Plugin:
;;   "watch:alert"     — User reached label threshold, show toast
;;   "tracker:msg"     — Forward a message to the tracker channel
;;   "config:response" — Bot config response
;;   "status"          — Bot status update

(def event-to-bot
  "Plugin → Bot: forward an OpenPlanner event."
  "event")

(def label-added-to-bot
  "Plugin → Bot: a label was added to a message."
  "label:added")

(def label-removed-to-bot
  "Plugin → Bot: a label was removed from a message."
  "label:removed")

(def backfill-to-bot
  "Plugin → Bot: request a backfill."
  "backfill")

(def config-request-to-bot
  "Plugin → Bot: request current bot config."
  "config:request")

(def plugin-identify-to-bot
  "Plugin → Bot: identify the connected plugin instance."
  "plugin:identify")

(def watch-alert-to-plugin
  "Bot → Plugin: a user reached the label threshold."
  "watch:alert")

(def tracker-msg-to-plugin
  "Bot → Plugin: forward a message to the tracker channel."
  "tracker:msg")

(def config-response-to-plugin
  "Bot → Plugin: respond with current bot config."
  "config:response")

(def status-to-plugin
  "Bot → Plugin: bot status update."
  "status")
