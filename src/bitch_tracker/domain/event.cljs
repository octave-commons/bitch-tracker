(ns bitch-tracker.domain.event
  "Pure constructors for OpenPlanner event shapes.
  No I/O. Inputs are raw JS objects from Discord; output is a CLJS map."
  (:require [bitch-tracker.domain.policy :as c]
            [bitch-tracker.shape.support :as u]))

(def ^:private schema-version 1)
(def ^:private schema-name    "openplanner.event.v1")

(defn- event-id
  "Builds a namespaced event ID from source kind/channel/message coordinates."
  [source channel-id message-id]
  (str "discord:" source ":" channel-id ":" message-id))

(defn author-id
  "Resolves the author's Discord ID from a message object, returning empty string when absent."
  [message]
  (let [author (u/jget message "author")]
    (str (or (when (or (string? author) (number? author)) author)
             (u/jget author "id")
             (u/jget message "authorId")
             (u/jget message "author_id")
             (u/jget message "userId")
             ""))))

(defn author-name
  "Resolves the author's display name, returning empty string when absent."
  [message]
  (let [author (u/jget message "author")]
    (str (or (u/jget author "globalName")
             (u/jget author "username")
             (u/jget message "authorUsername")
             ""))))

(defn- author-bot?
  "Returns true when the message author is a bot account."
  [message]
  (let [author (u/jget message "author")]
    (boolean (or (u/jget author "bot") (u/jget message "isBot")))))

(defn- attachment-list
  "Converts the message's attachments collection to a vector of CLJS maps."
  [message]
  (->> (u/js-seq (u/jget message "attachments"))
       (mapv (fn [a]
               {:id           (str (or (u/jget a "id") ""))
                :filename     (str (or (u/jget a "filename") ""))
                :url          (str (or (u/jget a "url") ""))
                :content-type (str (or (u/jget a "contentType") (u/jget a "content_type") ""))
                :size         (or (u/jget a "size") 0)}))))

(defn- embed-list
  "Converts the message's embeds to a vector of CLJS maps."
  [message]
  (->> (u/js-seq (u/jget message "embeds"))
       (mapv (fn [e]
               {:type  (str (or (u/jget e "type") ""))
                :title (str (or (u/jget e "title") ""))
                :url   (str (or (u/jget e "url") ""))}))))

(defn- channel-name
  "Returns the channel name string, or empty string when absent."
  [channel]
  (str (or (u/jget channel "name") "")))

(defn label-emoji?
  "Returns true when emoji-name or emoji are recognised label emoji."
  [emoji-name emoji]
  (or (contains? c/label-emoji-names emoji-name)
      (contains? c/label-emoji-names emoji)))

(defn message-to-event
  "Builds an OpenPlanner event map from a Discord message JS object."
  [message channel guild-id]
  (let [message-id  (str (or (u/jget message "id") ""))
        channel-id  (str (or (u/jget message "channel_id") (u/jget message "channelId") ""))
        content     (str (or (u/jget message "content") ""))
        aid         (author-id message)
        attachments (attachment-list message)]
    {:schema         schema-name
     :schema-version schema-version
     :id             (event-id "message" channel-id message-id)
     :ts             (str (or (u/jget message "timestamp") ""))
     :source         "discord"
     :kind           "discord.message"
     :source-ref     {:project "bitch-tracker"
                      :session guild-id
                      :message message-id}
     :text           content
     :meta           {:author           (author-name message)
                      :author-id        aid
                      :author-username  (str (or (u/jget (u/jget message "author") "username") ""))
                      :bot              (author-bot? message)
                      :tags             []}
     :extra          {:channel-id   channel-id
                      :channel-name (channel-name channel)
                      :guild-id     guild-id
                      :attachments  attachments
                      :embeds       (embed-list message)
                      :pinned       (boolean (u/jget message "pinned"))
                      :tts          (boolean (u/jget message "tts"))}}))

(defn reaction-to-event
  "Builds an OpenPlanner event map from a Discord reaction ADD payload."
  [payload channel guild-id emoji reactor-id _message]
  (let [message-id  (str (or (u/jget payload "messageId") ""))
        channel-id  (str (or (u/jget payload "channelId") ""))]
    {:schema         schema-name
     :schema-version schema-version
     :id             (event-id "reaction" channel-id (str message-id ":" emoji ":" reactor-id))
     :ts             (.toISOString (js/Date.))
     :source         "discord"
     :kind           "discord.reaction"
     :source-ref     {:project "bitch-tracker"
                      :session guild-id
                      :message message-id}
     :text           (str emoji " on message " message-id)
     :meta           {:author           reactor-id
                      :author-id        reactor-id
                      :author-username  ""
                      :bot              false
                      :tags             ["reaction"]}
     :extra          {:channel-id   channel-id
                      :channel-name (channel-name channel)
                      :guild-id     guild-id
                      :emoji        emoji
                      :reactor-id   reactor-id}}))
