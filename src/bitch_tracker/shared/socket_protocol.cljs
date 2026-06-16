(ns bitch-tracker.shared.socket-protocol)

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

;; Plugin → Bot events
(def event-to-bot "event")
(def label-added-to-bot "label:added")
(def label-removed-to-bot "label:removed")
(def backfill-to-bot "backfill")
(def config-request-to-bot "config:request")
(def plugin-identify-to-bot "plugin:identify")

;; Bot → Plugin events
(def watch-alert-to-plugin "watch:alert")
(def tracker-msg-to-plugin "tracker:msg")
(def config-response-to-plugin "config:response")
(def status-to-plugin "status")
