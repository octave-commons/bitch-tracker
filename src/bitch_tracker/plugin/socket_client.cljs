(ns bitch-tracker.plugin.socket-client
  (:require [bitch-tracker.shared.socket-protocol :as proto]))

(def socket-io-client (js/require "socket.io-client"))

(defn make-state []
  #js {:socket nil
       :user-info nil
       :connected false
       :reconnect-timer nil
       :on-watch-alert nil
       :on-tracker-msg nil
       :on-status nil})

(defn connect! [^js client-state url user-info handlers]
  (let [^js socket (.connect socket-io-client url
                          #js {:reconnection true
                               :reconnectionDelay 2000
                               :reconnectionAttempts js/Infinity})]
    (set! (.-socket client-state) socket)
    (set! (.-user-info client-state) user-info)
    (set! (.-on-watch-alert client-state) (:on-watch-alert handlers))
    (set! (.-on-tracker-msg client-state) (:on-tracker-msg handlers))
    (set! (.-on-status client-state) (:on-status handlers))

    (.on socket "connect"
         (fn []
           (set! (.-connected client-state) true)
           (js/console.log "[socket] Connected to bot server")
           (.emit socket proto/plugin-identify-to-bot (or (.-user-info client-state) #js {}))
           (when-let [^js cb (.-on-status client-state)]
             (cb #js {:status "connected"}))))

    (.on socket "disconnect"
         (fn []
           (set! (.-connected client-state) false)
           (js/console.log "[socket] Disconnected from bot server")
           (when-let [^js cb (.-on-status client-state)]
             (cb #js {:status "disconnected"}))))

    (.on socket proto/watch-alert-to-plugin
         (fn [data]
           (js/console.log "[socket] Watch alert received")
           (when-let [^js cb (.-on-watch-alert client-state)]
             (cb data))))

    (.on socket proto/tracker-msg-to-plugin
         (fn [data]
           (when-let [^js cb (.-on-tracker-msg client-state)]
             (cb data))))

    (.on socket proto/status-to-plugin
         (fn [data]
           (when-let [^js cb (.-on-status client-state)]
             (cb data))))

    (.on socket "connect_error"
         (fn [err]
           (js/console.warn "[socket] Connection error:" (.-message err))))

    client-state))

(defn emit! [^js client-state event-name data]
  (when-let [^js socket (.-socket client-state)]
    (when (.-connected client-state)
      (.emit socket event-name data))))

(defn send-event! [^js client-state event]
  (emit! client-state proto/event-to-bot event))

(defn send-label-added! [^js client-state data]
  (emit! client-state proto/label-added-to-bot data))

(defn send-label-removed! [^js client-state data]
  (emit! client-state proto/label-removed-to-bot data))

(defn send-backfill! [^js client-state data]
  (emit! client-state proto/backfill-to-bot data))

(defn request-config! [^js client-state]
  (emit! client-state proto/config-request-to-bot #js {}))

(defn disconnect! [^js client-state]
  (when-let [^js socket (.-socket client-state)]
    (.disconnect socket)
    (set! (.-socket client-state) nil)
    (set! (.-connected client-state) false)))
