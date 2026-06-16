(ns bitch-tracker.shape.protocol
  "Wire-name constants for the socket.io message protocol.
  Pure data; no I/O, no domain policy.")

(def ^:const event-to-bot
  "Wire name for a tracker event sent to the bot."
  "tracker:event")

(def ^:const label-added-to-bot
  "Wire name for a label-added event sent to the bot."
  "tracker:label:add")

(def ^:const label-removed-to-bot
  "Wire name for a label-removed event sent to the bot."
  "tracker:label:remove")

(def ^:const backfill-to-bot
  "Wire name for a backfill request sent to the bot."
  "tracker:backfill")

(def ^:const config-request-to-bot
  "Wire name for a bot-config request sent to the bot."
  "tracker:config:get")

(def ^:const plugin-identify-to-bot
  "Wire name for the plugin identity handshake sent to the bot."
  "plugin:identify")

(def ^:const watch-alert-to-plugin
  "Wire name for a watch alert delivered to the plugin."
  "bot:watch:alert")

(def ^:const tracker-msg-to-plugin
  "Wire name for a tracker message delivered to the plugin."
  "bot:tracker:message")

(def ^:const status-to-plugin
  "Wire name for a status update delivered to the plugin."
  "bot:status")

(def ^:const config-response-to-plugin
  "Wire name for a bot-config response delivered to the plugin."
  "bot:config:response")
