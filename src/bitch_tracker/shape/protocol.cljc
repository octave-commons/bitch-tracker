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

(def ^:const lens-ask-to-bot
  "Wire name for a KnoxxLens question (message + context items) sent to the bot."
  "lens:ask")

(def ^:const lens-abort-to-bot
  "Wire name for a KnoxxLens abort request sent to the bot."
  "lens:abort")

(def ^:const lens-config-request-to-bot
  "Wire name for a KnoxxLens config request sent to the bot."
  "lens:config:get")

(def ^:const lens-config-response-to-plugin
  "Wire name for the KnoxxLens config response delivered to the plugin."
  "bot:lens:config")

(def ^:const lens-accepted-to-plugin
  "Wire name for a KnoxxLens turn acceptance (run/session ids) delivered to the plugin."
  "bot:lens:accepted")

(def ^:const lens-token-to-plugin
  "Wire name for a streamed KnoxxLens assistant token delivered to the plugin."
  "bot:lens:token")

(def ^:const lens-event-to-plugin
  "Wire name for a KnoxxLens run event (tool start/end, hydration) delivered to the plugin."
  "bot:lens:event")

(def ^:const lens-done-to-plugin
  "Wire name for a completed KnoxxLens turn delivered to the plugin."
  "bot:lens:done")

(def ^:const lens-error-to-plugin
  "Wire name for a failed or refused KnoxxLens turn delivered to the plugin."
  "bot:lens:error")
