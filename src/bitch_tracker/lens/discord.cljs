(ns bitch-tracker.lens.discord
  "Discord client interop for KnoxxLens: stores, the open channel, loaded
  messages, and the message context menu. All I/O; no domain policy."
  (:require [bitch-tracker.domain.lens-context :as ctx]
            [bitch-tracker.plugin.bd :as bd]
            [bitch-tracker.shared.events :as events]
            [bitch-tracker.shared.util :as u]))

(defn load-stores
  "Resolves the Discord stores KnoxxLens reads from."
  []
  {:dispatcher (bd/get-module (fn [m] (and (u/jget m "dispatch") (u/jget m "subscribe") (u/jget m "unsubscribe"))))
   :channel-store (bd/get-store "ChannelStore")
   :guild-store (bd/get-store "GuildStore")
   :message-store (bd/get-store "MessageStore")
   :selected-channel-store (bd/get-store "SelectedChannelStore")
   :user-store (bd/get-store "UserStore")})

(defn current-user
  "Returns {:id :username} for the logged-in account."
  [{:keys [user-store]}]
  (let [user (u/jcall user-store "getCurrentUser")]
    {:id (str (or (u/jget user "id") ""))
     :username (str (or (u/jget user "username") (u/jget user "globalName") ""))}))

(defn subscribe!
  "Subscribes handler to a dispatcher action type. Returns an unsubscribe thunk."
  [{:keys [dispatcher]} action-type handler]
  (when (u/jget dispatcher "subscribe")
    (u/jcall dispatcher "subscribe" action-type handler)
    (fn [] (u/jcall dispatcher "unsubscribe" action-type handler))))

(defn selected-channel-id
  "Returns the id of the channel open in the client, or nil."
  [{:keys [selected-channel-store]}]
  (some-> (u/jcall selected-channel-store "getChannelId") str))

(defn- raw-channel [{:keys [channel-store]} channel-id]
  (u/jcall channel-store "getChannel" channel-id))

(defn- guild-id-of [^js channel]
  (str (or (u/jget channel "guild_id") (u/jcall channel "getGuildId") "")))

(defn channel-info
  "Returns {:id :name :guild-id :guild-name} for a channel id."
  [{:keys [guild-store] :as stores} channel-id]
  (let [channel (raw-channel stores channel-id)
        guild-id (guild-id-of channel)]
    {:id (str channel-id)
     :name (str (or (u/jget channel "name") (u/jget channel "rawRecipients" 0 "username") "direct-message"))
     :guild-id guild-id
     :guild-name (str (or (u/jget (u/jcall guild-store "getGuild" guild-id) "name") (if (seq guild-id) guild-id "DM")))}))

(defn message->context
  "Converts a Discord message into a context message via the shared OpenPlanner event builder."
  [stores ^js message channel-id]
  (let [channel (raw-channel stores channel-id)
        event (events/message-to-event message channel (guild-id-of channel) #{})]
    (ctx/event->context-message (js->clj event :keywordize-keys true))))

(defn loaded-messages
  "Returns the channel's messages already loaded in the client, oldest first, as context messages."
  [{:keys [message-store] :as stores} channel-id]
  (->> (u/js-values (u/jcall message-store "getMessages" channel-id))
       (remove #(or (= "SENDING" (u/jget % "state")) (= 8 (u/jget % "type"))))
       (mapv #(message->context stores % channel-id))))

(defn patch-message-menu!
  "Adds an 'Add to Knoxx context' item to Discord's message context menu.
  Calls (on-add message channel-id). Returns the unpatch thunk, or nil."
  [plugin-name on-add]
  (let [context-menu (u/jget (bd/bd-api) "ContextMenu")]
    (when (u/jget context-menu "patch")
      (u/jcall context-menu "patch" "message"
               (fn [^js tree ^js props]
                 (let [message (u/jget props "message")
                       channel-id (or (u/jget props "channel" "id") (u/jget message "channel_id"))
                       item (u/jcall context-menu "buildItem"
                                     #js {:id "knoxx-lens-add-context"
                                          :label "Add to Knoxx context"
                                          :action (fn [] (on-add message (str channel-id)))})
                       children (u/jget tree "props" "children")]
                   (when (and message item)
                     (try
                       (if (array? children)
                         (.push children item)
                         (aset (u/jget tree "props") "children" #js [children item]))
                       (catch :default err
                         (bd/log! "warn" plugin-name "context menu patch failed" err))))))))))
