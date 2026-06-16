(ns bitch-tracker.shared.events
  (:require [clojure.string :as str]
            [bitch-tracker.shared.constants :as c]
            [bitch-tracker.shared.util :as u]))

(defn author-id [message]
  (let [author (u/jget message "author")]
    (str (or (when (or (string? author) (number? author)) author)
             (u/jget author "id")
             (u/jget message "authorId")
             (u/jget message "author_id")
             (u/jget message "userId")
             (u/jget message "user_id")
             ""))))

(defn ids [value]
  (->> (u/js-values value)
       (map #(str (or (u/jget % "id") %)))
       (remove str/blank?)
       vec))

(defn label-emoji?
  ([emoji] (label-emoji? emoji emoji))
  ([emoji-name emoji]
   (some #(or (= (str emoji-name) %) (= (str emoji) %)) [c/poodle-emoji c/clown-emoji])))

(defn message-to-event [message channel guild-id]
  (let [author (or (u/jget message "author") #js {})
        aid (str (or (u/jget author "id") (u/jget message "author_id") "unknown"))
        content (if (string? (u/jget message "content")) (u/jget message "content") "")
        attachments (map (fn [a]
                           {:id (str (or (u/jget a "id") ""))
                            :filename (or (u/jget a "filename") (u/jget a "name"))
                            :content_type (or (u/jget a "content_type") (u/jget a "contentType"))
                            :size (u/jget a "size")
                            :url (u/jget a "url")
                            :proxy_url (or (u/jget a "proxy_url") (u/jget a "proxyURL"))
                            :width (u/jget a "width")
                            :height (u/jget a "height")})
                         (u/js-values (u/jget message "attachments")))
        embeds (map (fn [e]
                      {:type (u/jget e "type")
                       :title (u/jget e "title")
                       :description (u/jget e "description")
                       :url (u/jget e "url")
                       :provider (u/jget e "provider" "name")})
                    (u/js-values (u/jget message "embeds")))
        ts (.toISOString (js/Date. (or (u/jget message "timestamp") (u/jget message "timestamp" "_i") (.getTime (js/Date.)))))
        labels (cond-> []
                 (contains? c/known-label-user-ids aid) (conj "moderation-watch:known-user" (str "moderation-watch:user:" aid)))]
    (clj->js {:schema "openplanner.event.v1"
              :schema_version 1
              :id (str "discord:" guild-id ":" (or (u/jget message "channel_id") (u/jget message "channelId")) ":" (u/jget message "id"))
              :ts ts
              :source "betterdiscord-openplanner"
              :kind "discord.message"
              :source_ref {:project c/default-project
                           :session guild-id
                           :message (str (u/jget message "id"))}
              :text (or (not-empty content)
                        (not-empty (str/join "\n" (keep :url attachments)))
                        (not-empty (str/join "\n" (keep :url embeds)))
                        "")
              :meta {:author (or (u/jget author "username") (u/jget author "globalName") aid)
                     :author_id aid
                     :author_username (u/jget author "username")
                     :author_global_name (u/jget author "globalName")
                     :bot (boolean (u/jget author "bot"))
                     :tags (cond-> ["discord" "message"]
                             (contains? c/known-label-user-ids aid) (into ["known-watch-user" "moderation-watch"]))}
              :extra (cond-> {:guild_id guild-id
                               :channel_id (str (or (u/jget message "channel_id") (u/jget message "channelId")))
                               :channel_name (u/jget channel "name")
                               :message_id (str (u/jget message "id"))
                               :nonce (u/jget message "nonce")
                               :type (u/jget message "type")
                               :flags (u/jget message "flags")
                               :pinned (boolean (u/jget message "pinned"))
                               :tts (boolean (u/jget message "tts"))
                               :mention_everyone (boolean (u/jget message "mention_everyone"))
                               :mentions (ids (u/jget message "mentions"))
                               :mention_roles (ids (u/jget message "mention_roles"))
                               :attachments attachments
                               :embeds embeds
                               :edited_timestamp (u/jget message "edited_timestamp")}
                       (seq labels) (assoc :openplanner_labels {:claim_system "discord-moderation-watch-v1"
                                                                 :labels (vec (distinct labels))
                                                                 :updated_at (u/now-iso)})
                       (contains? c/known-label-user-ids aid) (assoc :is_known_watch_user true))})))

(defn reaction-to-event [reaction _channel guild-id emoji user-id quality]
  (let [message-id (str (or (u/jget reaction "messageId") ""))
        channel-id (str (or (u/jget reaction "channelId") ""))
        base-label (u/reaction-label emoji)
        label? (label-emoji? emoji)
        quality-value (or quality (u/quality-from-emoji emoji))
        labels (cond-> [(str "reaction:" base-label)]
                 quality-value (conj (str "quality:" quality-value))
                 label? (into ["moderation:poodle" "moderation-watch:poodle-label"]))]
    (when (and (u/present-string? message-id) (u/present-string? channel-id))
      (clj->js {:schema "openplanner.event.v1"
                :schema_version 1
                :id (str "discord:reaction:" guild-id ":" channel-id ":" message-id ":" emoji ":" user-id)
                :ts (u/now-iso)
                :source "betterdiscord-openplanner"
                :kind "discord.reaction"
                :source_ref {:project c/default-project
                             :session guild-id
                             :message message-id}
                :text (str "Reaction: " emoji)
                :meta {:author user-id
                       :author_id user-id
                       :tags (cond-> ["discord" "reaction" "reaction-label"]
                               quality-value (conj "quality-label")
                               label? (into ["moderation-label" "poodle-label"]))}
                :extra (cond-> {:guild_id guild-id
                                 :channel_id channel-id
                                 :message_id message-id
                                 :reaction_emoji emoji
                                 :reaction_user_id user-id
                                 :openplanner_labels {:claim_system (cond quality-value "discord-quality-v1"
                                                                            label? "discord-moderation-watch-v1"
                                                                            :else "discord-reaction-v1")
                                                      :reaction_emojis [emoji]
                                                      :labels labels
                                                      :updated_at (u/now-iso)}}
                         quality-value (assoc :quality quality-value))}))))
