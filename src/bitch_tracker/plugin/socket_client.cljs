(ns bitch-tracker.plugin.socket-client
  "socket.io-client transport layer for the BetterDiscord plugin.
  All I/O; no domain policy."
  (:require [bitch-tracker.shape.protocol :as proto]
            [bitch-tracker.shape.support :as u]))

(defn make-state
  "Returns a fresh socket client state object."
  []
  #js {:socket nil :connected false})

(defn- get-socket-io
  "Resolves the socket.io-client constructor from the page environment."
  []
  (or (u/jget js/globalThis "io")
      (u/jget js/globalThis "SocketIO")
      (u/jget js/window "io")))

(defn connect!
  "Opens a socket.io connection to url with the given identity map and event callbacks."
  [state url identity handlers]
  (let [io-ctor (get-socket-io)]
    (when-not io-ctor
      (js/console.warn "[socket-client] socket.io not found on window"))
    (when io-ctor
      (let [socket (.call io-ctor nil url #js {:transports #js ["websocket"] :auth identity})]
        (aset state "socket" socket)
        (.on socket "connect"
             (fn []
               (aset state "connected" true)
               (js/console.log "[socket-client] connected")))
        (.on socket "disconnect"
             (fn []
               (aset state "connected" false)
               (js/console.log "[socket-client] disconnected")))
        (.on socket proto/watch-alert-to-plugin
             (fn [data] (when-let [cb (:on-watch-alert handlers)] (cb data))))
        (.on socket proto/tracker-msg-to-plugin
             (fn [data] (when-let [cb (:on-tracker-msg handlers)] (cb data))))
        (.on socket proto/status-to-plugin
             (fn [data] (when-let [cb (:on-status handlers)] (cb data))))
        socket))))

(defn disconnect!
  "Closes the socket connection if one is open."
  [state]
  (when-let [socket (aget state "socket")]
    (.disconnect socket)
    (aset state "socket" nil)
    (aset state "connected" false)))

(defn send-event!
  "Emits an OpenPlanner event to the bot."
  [state event]
  (when-let [socket (aget state "socket")]
    (.emit socket proto/event-to-bot event)))

(defn send-label-added!
  "Emits a label-added payload to the bot."
  [state data]
  (when-let [socket (aget state "socket")]
    (.emit socket proto/label-added-to-bot data)))

(defn send-label-removed!
  "Emits a label-removed payload to the bot."
  [state data]
  (when-let [socket (aget state "socket")]
    (.emit socket proto/label-removed-to-bot data)))

(defn send-backfill!
  "Emits a backfill request to the bot."
  [state data]
  (when-let [socket (aget state "socket")]
    (.emit socket proto/backfill-to-bot data)))

(defn request-config!
  "Requests the current bot config from the bot."
  [state]
  (when-let [socket (aget state "socket")]
    (.emit socket proto/config-request-to-bot)))
