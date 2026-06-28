(ns bitch-tracker.plugin.socket-client
  "socket.io-client transport layer for the BetterDiscord plugin.
  All I/O; no domain policy."
  (:require ["socket.io-client" :as socket-io]
            [bitch-tracker.shape.protocol :as proto]))

(defn make-state
  "Returns a fresh socket client state object."
  []
  #js {:socket nil
       :connected false
       :user-info nil
       :reconnect-timer nil
       :bot-config nil
       :on-watch-alert nil
       :on-tracker-msg nil
       :on-status nil})

(defn- call-cb
  "Invokes a callback stored on the JS state object, if present."
  [state k data]
  (when-let [cb (aget state k)]
    (cb data)))

(defn connect!
  "Opens a socket.io connection to url with the given user-info map and event callbacks."
  [state url user-info handlers]
  (let [^js socket (socket-io url #js {:transports #js ["websocket"]})]
    (aset state "socket" socket)
    (aset state "user-info" user-info)
    (aset state "on-watch-alert" (:on-watch-alert handlers))
    (aset state "on-tracker-msg" (:on-tracker-msg handlers))
    (aset state "on-status" (:on-status handlers))
    (.on ^js socket "connect"
         (fn []
           (aset state "connected" true)
           (js/console.log "[socket-client] connected")
           (.emit ^js socket proto/plugin-identify-to-bot (or user-info #js {}))
           (call-cb state "on-status" #js {:status "connected"})))
    (.on ^js socket "disconnect"
         (fn []
           (aset state "connected" false)
           (js/console.log "[socket-client] disconnected")
           (call-cb state "on-status" #js {:status "disconnected"})))
    (.on ^js socket proto/watch-alert-to-plugin
         (fn [data]
           (js/console.log "[socket-client] watch alert received")
           (call-cb state "on-watch-alert" data)))
    (.on ^js socket proto/tracker-msg-to-plugin
         (fn [data]
           (call-cb state "on-tracker-msg" data)))
    (.on ^js socket proto/status-to-plugin
         (fn [data]
           (call-cb state "on-status" data)))
    (.on ^js socket proto/config-response-to-plugin
         (fn [data]
           (aset state "bot-config" data)
           (js/console.log "[socket-client] bot config received" data)))
    (.on ^js socket "connect_error"
         (fn [err]
           (js/console.warn "[socket-client] connection error:" (.-message err))))
    socket))

(defn emit!
  "Emits event-name with data only when a socket exists and is connected."
  [state event-name data]
  (let [^js socket (aget state "socket")]
    (when (and socket (aget state "connected"))
      (.emit ^js socket event-name data))))

(defn send-event!
  "Emits an OpenPlanner event to the bot."
  [state event]
  (emit! state proto/event-to-bot event))

(defn send-label-added!
  "Emits a label-added payload to the bot."
  [state data]
  (emit! state proto/label-added-to-bot data))

(defn send-label-removed!
  "Emits a label-removed payload to the bot."
  [state data]
  (emit! state proto/label-removed-to-bot data))

(defn send-backfill!
  "Emits a backfill request to the bot."
  [state data]
  (emit! state proto/backfill-to-bot data))

(defn request-config!
  "Requests the current bot config from the bot."
  [state]
  (emit! state proto/config-request-to-bot #js {}))

(defn bot-config
  "Returns the cached bot config, or an empty JS object if none."
  [state]
  (or (aget state "bot-config") #js {}))

(defn disconnect!
  "Closes the socket connection if one is open."
  [state]
  (when-let [^js socket (aget state "socket")]
    (.disconnect ^js socket)
    (aset state "socket" nil)
    (aset state "connected" false)))
