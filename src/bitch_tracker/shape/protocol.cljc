(ns bitch-tracker.shape.protocol
  "Wire-name constants for the socket.io message protocol.
  Pure data; no I/O, no domain policy.")

(def event-to-bot          "tracker:event")
(def label-added-to-bot    "tracker:label:add")
(def label-removed-to-bot  "tracker:label:remove")
(def backfill-to-bot       "tracker:backfill")
(def config-request-to-bot "tracker:config:get")

(def watch-alert-to-plugin  "bot:watch:alert")
(def tracker-msg-to-plugin  "bot:tracker:message")
(def status-to-plugin       "bot:status")
