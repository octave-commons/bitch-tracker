(ns bitch-tracker.lens.discord
  "Discord client interop for KnoxxLens: stores, the open channel, loaded
  messages, and the message context menu. All I/O; no domain policy."
  (:require [bitch-tracker.domain.lens-context :as ctx]
            [bitch-tracker.plugin.bd :as bd]
            [bitch-tracker.shared.events :as events]
            [bitch-tracker.shared.util :as u]
            [clojure.string :as str]))

(defn load-stores
  "Resolves the Discord stores and action modules KnoxxLens interacts with."
  []
  {:dispatcher (bd/get-module (fn [m] (and (u/jget m "dispatch") (u/jget m "subscribe") (u/jget m "unsubscribe"))))
   :component-dispatch (bd/get-module (fn [m] (and (u/jget m "dispatchToLastSubscribed") (u/jget m "emitter"))))
   :message-actions (bd/get-module (fn [m] (and (u/jget m "sendMessage") (u/jget m "editMessage"))))
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
  [{:keys [guild-store] :as stores} ^js message channel-id]
  (let [channel (raw-channel stores channel-id)
        guild-id (guild-id-of channel)
        guild-name (when (seq guild-id)
                     (some-> (u/jcall guild-store "getGuild" guild-id) (u/jget "name") str str/trim not-empty))
        event (events/message-to-event message channel guild-id #{} guild-name)]
    (ctx/event->context-message (js->clj event :keywordize-keys true))))

(defn loaded-messages
  "Returns the channel's messages already loaded in the client, oldest first, as context messages."
  [{:keys [message-store] :as stores} channel-id]
  (->> (u/js-values (u/jcall message-store "getMessages" channel-id))
       (remove #(or (= "SENDING" (u/jget % "state")) (= 8 (u/jget % "type"))))
       (mapv #(message->context stores % channel-id))))

(defn action-channel-id
  "Extracts the channel id from a Dispatcher action payload, or nil."
  [^js action]
  (let [cid (or (u/jget action "channelId")
                (u/jget action "channel_id")
                (u/jget action "message" "channel_id")
                (u/jget action "message" "channelId"))]
    (when (u/present-string? cid)
      (str cid))))

(defn action-messages
  "Extracts context messages from a Dispatcher action payload (LOAD_MESSAGES_SUCCESS, MESSAGE_CREATE, MESSAGE_UPDATE)."
  [stores ^js action]
  (when-let [channel-id (action-channel-id action)]
    (let [raw-msgs (cond
                     (u/jget action "messages") (u/js-values (u/jget action "messages"))
                     (u/jget action "message") [(u/jget action "message")]
                     :else [])]
      (->> raw-msgs
           (remove #(or (nil? %)
                        (= "SENDING" (u/jget % "state"))
                        (= 8 (u/jget % "type"))
                        (str/blank? (str (u/jget % "id")))))
           (mapv #(message->context stores % channel-id))))))

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

(defn insert-into-composer!
  "Stages draft text into Discord's channel composer (via ComponentDispatch INSERT_TEXT
  and Dispatcher DRAFT_SAVE) and copies it to the clipboard as backup."
  [{:keys [dispatcher component-dispatch]} channel-id text]
  (let [clean (str (or text ""))]
    (when-let [clipboard (some-> (bd/global-get "navigator") .-clipboard)]
      (.writeText clipboard clean))
    (try
      (when (and dispatcher (u/jget dispatcher "dispatch") (seq (str channel-id)))
        (u/jcall dispatcher "dispatch"
                 #js {:type "DRAFT_SAVE"
                      :channelId (str channel-id)
                      :draft clean
                      :draftType 0}))
      (catch :default _ nil))
    (try
      (when (and component-dispatch (u/jget component-dispatch "dispatchToLastSubscribed"))
        (u/jcall component-dispatch "dispatchToLastSubscribed"
                 "INSERT_TEXT"
                 #js {:plainText clean :rawText clean}))
      (catch :default _ nil))
    {:ok true :mode :inserted}))

(defn send-approved-message!
  "Sends a human-approved draft message to channel-id using Discord's MessageActions
  module (on behalf of the logged-in operator after explicit approval), falling back
  to staging in the composer if MessageActions is unavailable."
  [{:keys [message-actions] :as stores} channel-id text]
  (let [clean (str/trim (str (or text "")))]
    (cond
      (str/blank? clean)
      {:ok false :error "Draft text is empty"}

      (str/blank? (str channel-id))
      {:ok false :error "No target channel selected"}

      (and message-actions (u/jget message-actions "sendMessage"))
      (try
        (u/jcall message-actions "sendMessage"
                 (str channel-id)
                 #js {:content clean
                      :tts false
                      :invalidEmojis #js []
                      :validNonShortcutEmojis #js []})
        {:ok true :mode :sent}
        (catch :default err
          (insert-into-composer! stores channel-id clean)
          {:ok true :mode :staged :warning (str (.-message err))}))

      :else
      (do
        (insert-into-composer! stores channel-id clean)
        {:ok true :mode :staged}))))
