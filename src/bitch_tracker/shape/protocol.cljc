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

(def ^:const lens-sessions-list-to-bot
  "Wire name for requesting past KnoxxLens sessions from the bot."
  "lens:sessions:list")

(def ^:const lens-sessions-list-to-plugin
  "Wire name for past KnoxxLens sessions delivered to the plugin."
  "bot:lens:sessions")

(def ^:const lens-session-get-to-bot
  "Wire name for requesting full turns of a past KnoxxLens session from the bot."
  "lens:session:get")

(def ^:const lens-session-get-to-plugin
  "Wire name for full turns of a past KnoxxLens session delivered to the plugin."
  "bot:lens:session")

(def ^:const lens-graph-query-to-bot
  "Wire name for requesting a knowledge-graph neighborhood query from the bot."
  "lens:graph:query")

(def ^:const lens-graph-query-to-plugin
  "Wire name for a knowledge-graph neighborhood response delivered to the plugin."
  "bot:lens:graph")

(def ^:const lens-topics-extract-to-bot
  "Wire name for requesting NER + POS noun/proper-noun topic extraction from the bot."
  "lens:topics:extract")

(def ^:const lens-topics-extract-to-plugin
  "Wire name for extracted NER + POS channel topics delivered to the plugin."
  "bot:lens:topics")

(def ^:const lens-connections-to-bot
  "Wire name for requesting WebGL connections graph from the bot."
  "lens:connections:get")

(def ^:const lens-connections-to-plugin
  "Wire name for WebGL connections graph delivered to the plugin."
  "bot:lens:connections")

(def ^:const lens-govone-extract-to-bot
  "Wire name for GovOne de-identification and extraction from the bot."
  "lens:govone:extract")

(def ^:const lens-govone-extract-to-plugin
  "Wire name for GovOne de-identification response delivered to the plugin."
  "bot:lens:govone:extract")

(def ^:const lens-govone-channel-read-to-bot
  "Wire name for reading governed channel messages via GovOne."
  "lens:govone:channel:read")

(def ^:const lens-govone-channel-read-to-plugin
  "Wire name for governed channel messages delivered to the plugin."
  "bot:lens:govone:channel:read")

(def ^:const lens-govone-channel-send-to-bot
  "Wire name for sending a message over the governed channel via GovOne."
  "lens:govone:channel:send")

(def ^:const lens-govone-channel-send-to-plugin
  "Wire name for governed channel send response delivered to the plugin."
  "bot:lens:govone:channel:send")

(def ^:const lens-desktop-list-to-bot
  "Wire name for requesting active desktop windows from the bot."
  "lens:desktop:list")

(def ^:const lens-desktop-list-to-plugin
  "Wire name for active desktop windows delivered to the plugin."
  "bot:lens:desktop:list")

(def ^:const lens-desktop-focus-to-bot
  "Wire name for requesting i3 window focus from the bot."
  "lens:desktop:focus")

(def ^:const lens-desktop-focus-to-plugin
  "Wire name for i3 window focus response delivered to the plugin."
  "bot:lens:desktop:focus")

