(ns bitch-tracker.shared.events
  (:require [bitch-tracker.shared.constants :as c]
            [bitch-tracker.shared.util :as u]
            [clojure.string :as str]))

(defn author-id
  "Extract a string author id from a Discord message object."
  [message]
  (let [author (u/jget message "author")]
    (str (or (when (or (string? author) (number? author)) author)
             (u/jget author "id")
             (u/jget message "authorId")
             (u/jget message "author_id")
             (u/jget message "userId")
             (u/jget message "user_id")
             ""))))

(defn ids
  "Return a vector of non-blank string ids from a Discord collection."
  [value]
  (->> (u/js-values value)
       (map #(str (or (u/jget % "id") %)))
       (remove str/blank?)
       distinct
       vec))

(defn extract-urls
  "Extracts distinct HTTP(S) URLs from a string."
  [text]
  (if (str/blank? (str text))
    []
    (->> (re-seq #"https?://[^\s<>\"')\]]+" (str text))
         (map #(str/replace % #"[.,;:!?]+$" ""))
         (remove str/blank?)
         distinct
         vec)))

(defn extract-user-mentions
  "Extracts distinct user IDs from <@id> or <@!id> Discord mention syntax in text."
  [text]
  (if (str/blank? (str text))
    []
    (->> (re-seq #"<@!?(\d+)>" (str text))
         (map second)
         (remove str/blank?)
         distinct
         vec)))

(defn extract-channel-mentions
  "Extracts distinct channel IDs from <#id> Discord channel mention syntax in text."
  [text]
  (if (str/blank? (str text))
    []
    (->> (re-seq #"<#(\d+)>" (str text))
         (map second)
         (remove str/blank?)
         distinct
         vec)))

(defn- mention-user-records
  "Extracts structured {:id :username :global_name} maps from message.mentions."
  [message]
  (->> (u/js-values (u/jget message "mentions"))
       (keep (fn [m]
               (let [uid (str (or (u/jget m "id") (when (or (string? m) (number? m)) m) ""))]
                 (when-not (str/blank? uid)
                   {:id uid
                    :username (u/jget m "username")
                    :global_name (or (u/jget m "globalName") (u/jget m "global_name"))}))))
       vec))

(defn- reply-reference
  "Extracts {:message_id :channel_id :guild_id :author_id :author_username} when message is a reply."
  [message]
  (let [msg-ref (or (u/jget message "messageReference") (u/jget message "message_reference"))
        ref-msg (or (u/jget message "referencedMessage") (u/jget message "referenced_message"))
        ref-mid (str (or (u/jget msg-ref "message_id")
                         (u/jget msg-ref "messageId")
                         (u/jget ref-msg "id")
                         ""))
        ref-cid (str (or (u/jget msg-ref "channel_id")
                         (u/jget msg-ref "channelId")
                         (u/jget ref-msg "channel_id")
                         ""))
        ref-gid (str (or (u/jget msg-ref "guild_id")
                         (u/jget msg-ref "guildId")
                         ""))
        ref-aid (str (or (u/jget ref-msg "author" "id") ""))
        ref-aname (or (u/jget ref-msg "author" "globalName")
                      (u/jget ref-msg "author" "username"))]
    (when-not (str/blank? ref-mid)
      (cond-> {:message_id ref-mid}
        (not (str/blank? ref-cid)) (assoc :channel_id ref-cid)
        (not (str/blank? ref-gid)) (assoc :guild_id ref-gid)
        (not (str/blank? ref-aid)) (assoc :author_id ref-aid)
        (some? ref-aname) (assoc :author_username ref-aname)))))

(defn label-emoji?
  "Return true if the emoji is a configured moderation label emoji."
  ([emoji] (label-emoji? emoji emoji))
  ([emoji-name emoji]
   (some #(or (= (str emoji-name) %) (= (str emoji) %)) [c/poodle-emoji c/clown-emoji])))

(defn- message-timestamp
  "Return an ISO-8601 timestamp for a Discord message."
  [message]
  (let [ts (u/jget message "timestamp")
        ts-internal (u/jget message "timestamp" "_i")
        raw (if (some? ts) ts (if (some? ts-internal) ts-internal (.getTime (js/Date.))))
        date (js/Date. raw)]
    (if (js/isNaN (.getTime date))
      (.toISOString (js/Date.))
      (.toISOString date))))

(defn message-to-event
  "Convert a Discord message into an OpenPlanner event map."
  [message channel guild-id known-label-user-ids]
  (let [author (or (u/jget message "author") #js {})
        aid (str (or (u/jget author "id") (u/jget message "author_id") "unknown"))
        channel-id (str (or (u/jget message "channel_id") (u/jget message "channelId") (u/jget channel "id") ""))
        channel-name (u/jget channel "name")
        message-id (str (or (u/jget message "id") ""))
        content (if (string? (u/jget message "content")) (u/jget message "content") "")
        attachments (mapv (fn [a]
                            {:id (str (or (u/jget a "id") ""))
                             :filename (or (u/jget a "filename") (u/jget a "name"))
                             :content_type (or (u/jget a "content_type") (u/jget a "contentType"))
                             :size (u/jget a "size")
                             :url (u/jget a "url")
                             :proxy_url (or (u/jget a "proxy_url") (u/jget a "proxyURL"))
                             :width (u/jget a "width")
                             :height (u/jget a "height")})
                          (u/js-values (u/jget message "attachments")))
        embeds (mapv (fn [e]
                       {:type (u/jget e "type")
                        :title (u/jget e "title")
                        :description (u/jget e "description")
                        :url (u/jget e "url")
                        :provider (u/jget e "provider" "name")})
                     (u/js-values (u/jget message "embeds")))
        mention-users (mention-user-records message)
        mentions (->> (concat (ids (u/jget message "mentions"))
                              (extract-user-mentions content))
                      (remove str/blank?)
                      distinct
                      vec)
        mention-channels (extract-channel-mentions content)
        urls (->> (concat (extract-urls content)
                          (keep :url attachments)
                          (keep :url embeds))
                  (remove str/blank?)
                  distinct
                  vec)
        reply-ref (reply-reference message)
        ts (message-timestamp message)
        labels (cond-> []
                 (contains? known-label-user-ids aid) (conj "moderation-watch:known-user" (str "moderation-watch:user:" aid)))]
    (clj->js {:schema "openplanner.event.v1"
              :schema_version 1
              :id (str "discord:" guild-id ":" channel-id ":" message-id)
              :ts ts
              :source "betterdiscord-openplanner"
              :kind "discord.message"
              :source_ref {:project c/default-project
                           :session guild-id
                           :message message-id}
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
                             (contains? known-label-user-ids aid) (into ["known-watch-user" "moderation-watch"]))}
              :extra (cond-> {:guild_id guild-id
                              :channel_id channel-id
                              :channel_name channel-name
                              :message_id message-id
                              :nonce (u/jget message "nonce")
                              :type (u/jget message "type")
                              :flags (u/jget message "flags")
                              :pinned (boolean (u/jget message "pinned"))
                              :tts (boolean (u/jget message "tts"))
                              :mention_everyone (boolean (u/jget message "mention_everyone"))
                              :mentions mentions
                              :mention_users mention-users
                              :mention_channels mention-channels
                              :mention_roles (ids (u/jget message "mention_roles"))
                              :urls urls
                              :attachments attachments
                              :embeds embeds
                              :edited_timestamp (u/jget message "edited_timestamp")}
                       (some? reply-ref) (assoc :reply_to reply-ref
                                                :reply_to_message_id (:message_id reply-ref))
                       (some? (:author_id reply-ref)) (assoc :reply_to_author_id (:author_id reply-ref))
                       (seq labels) (assoc :openplanner_labels {:claim_system "discord-moderation-watch-v1"
                                                                :labels (vec (distinct labels))
                                                                :updated_at (u/now-iso)})
                       (contains? known-label-user-ids aid) (assoc :is_known_watch_user true))})))

(defn- graph-node-event
  "Builds a graph.node OpenPlanner event JS object."
  [{:keys [event-id ts project session node-id node-kind label text extra]}]
  (clj->js {:schema "openplanner.event.v1"
            :schema_version 1
            :id event-id
            :ts ts
            :source "betterdiscord-openplanner"
            :kind "graph.node"
            :source_ref {:project (or project c/default-project)
                         :session session
                         :message node-id}
            :text (str (or text label node-id))
            :meta {:author "discord-graph"
                   :tags ["discord" "graph.node" node-kind]}
            :extra (merge {:lake (or project c/default-project)
                           :node_id node-id
                           :node_type node-kind
                           :node_kind node-kind
                           :label label
                           :preview (str (or text label node-id))
                           :entity_key node-id}
                          extra)}))

(defn- graph-edge-event
  "Builds a graph.edge OpenPlanner event JS object."
  [{:keys [event-id ts project session edge-type layer source-node-id target-node-id source-lake target-lake extra]}]
  (clj->js {:schema "openplanner.event.v1"
            :schema_version 1
            :id event-id
            :ts ts
            :source "betterdiscord-openplanner"
            :kind "graph.edge"
            :source_ref {:project (or project c/default-project)
                         :session session
                         :message source-node-id}
            :text (str source-node-id " -" edge-type "-> " target-node-id)
            :meta {:author "discord-graph"
                   :tags ["discord" "graph.edge" edge-type]}
            :extra (merge {:lake (or project c/default-project)
                           :edge_id event-id
                           :edge_type edge-type
                           :edge_kind edge-type
                           :layer (or layer "discord")
                           :source_node_id source-node-id
                           :target_node_id target-node-id
                           :source_lake (or source-lake project c/default-project)
                           :target_lake (or target-lake project c/default-project)}
                          extra)}))

(defn message-graph-events
  "Derives structured graph.node and graph.edge events (users, channels, mentions,
  replies, web links) from a discord.message OpenPlanner event."
  [event]
  (if (not= "discord.message" (u/jget event "kind"))
    []
    (let [ev-id (str (or (u/jget event "id") ""))
          ts (str (or (u/jget event "ts") (u/now-iso)))
          project (str (or (u/jget event "source_ref" "project") c/default-project))
          guild-id (str (or (u/jget event "extra" "guild_id") (u/jget event "source_ref" "session") ""))
          channel-id (str (or (u/jget event "extra" "channel_id") ""))
          channel-name (str (or (u/jget event "extra" "channel_name") ""))
          aid (str (or (u/jget event "meta" "author_id") ""))
          author-username (u/jget event "meta" "author_username")
          author-global (u/jget event "meta" "author_global_name")
          author-label (str (or author-global author-username aid "unknown"))
          text (str (or (u/jget event "text") ""))
          mentions (->> (concat (ids (u/jget event "extra" "mentions"))
                                (extract-user-mentions text))
                        (remove str/blank?)
                        distinct
                        vec)
          mention-users-by-id (into {}
                                    (keep (fn [u-obj]
                                            (let [uid (str (or (u/jget u-obj "id") ""))]
                                              (when-not (str/blank? uid)
                                                [uid {:username (u/jget u-obj "username")
                                                      :global_name (u/jget u-obj "global_name")}]))))
                                    (u/js-values (u/jget event "extra" "mention_users")))
          mention-channels (->> (concat (map str (u/js-values (u/jget event "extra" "mention_channels")))
                                        (extract-channel-mentions text))
                                (remove str/blank?)
                                distinct
                                vec)
          urls (->> (concat (map str (u/js-values (u/jget event "extra" "urls")))
                            (extract-urls text))
                    (remove str/blank?)
                    distinct
                    vec)
          reply-mid (str (or (u/jget event "extra" "reply_to_message_id")
                             (u/jget event "extra" "reply_to" "message_id")
                             ""))
          reply-aid (str (or (u/jget event "extra" "reply_to_author_id")
                             (u/jget event "extra" "reply_to" "author_id")
                             ""))
          author-node-id (when (and (not (str/blank? aid)) (not= "unknown" aid))
                           (str "discord:user:" aid))
          channel-node-id (when-not (str/blank? channel-id)
                            (str "discord:channel:" channel-id))]
      (if (str/blank? ev-id)
        []
        (vec
         (concat
          ;; Author node + authored_by edge
          (when author-node-id
            [(graph-node-event {:event-id (str "graph.node:" author-node-id)
                                :ts ts :project project :session guild-id
                                :node-id author-node-id :node-kind "discord.user"
                                :label author-label
                                :text (str "Discord user " author-label
                                           (when (and author-username (not= author-label author-username))
                                             (str " (@" author-username ")"))
                                           " [" aid "]")
                                :extra {:user_id aid
                                        :username author-username
                                        :global_name author-global
                                        :guild_id guild-id}})
             (graph-edge-event {:event-id (str ev-id ":authored_by:" author-node-id)
                                :ts ts :project project :session guild-id
                                :edge-type "authored_by" :layer "social"
                                :source-node-id ev-id :target-node-id author-node-id
                                :extra {:user_id aid :author author-label :channel_id channel-id :guild_id guild-id}})])
          ;; Channel node + in_channel edge
          (when channel-node-id
            (let [ch-label (if (str/blank? channel-name) (str "#" channel-id) (str "#" channel-name))]
              [(graph-node-event {:event-id (str "graph.node:" channel-node-id)
                                  :ts ts :project project :session guild-id
                                  :node-id channel-node-id :node-kind "discord.channel"
                                  :label ch-label
                                  :text (str "Discord channel " ch-label " [" channel-id "]")
                                  :extra {:channel_id channel-id :channel_name channel-name :guild_id guild-id}})
               (graph-edge-event {:event-id (str ev-id ":in_channel:" channel-node-id)
                                  :ts ts :project project :session guild-id
                                  :edge-type "in_channel" :layer "structural"
                                  :source-node-id ev-id :target-node-id channel-node-id
                                  :extra {:channel_id channel-id :channel_name channel-name :guild_id guild-id}})]))
          ;; Mentioned user nodes + mentions_user edges
          (mapcat (fn [uid]
                    (let [target-id (str "discord:user:" uid)
                          u-info (get mention-users-by-id uid)
                          u-label (str (or (:global_name u-info) (:username u-info) uid))]
                      [(graph-node-event {:event-id (str "graph.node:" target-id)
                                          :ts ts :project project :session guild-id
                                          :node-id target-id :node-kind "discord.user"
                                          :label u-label
                                          :text (str "Discord user " u-label " [" uid "]")
                                          :extra {:user_id uid
                                                  :username (:username u-info)
                                                  :global_name (:global_name u-info)
                                                  :guild_id guild-id}})
                       (graph-edge-event {:event-id (str ev-id ":mentions_user:" target-id)
                                          :ts ts :project project :session guild-id
                                          :edge-type "mentions_user" :layer "social"
                                          :source-node-id ev-id :target-node-id target-id
                                          :extra {:mentioned_user_id uid :channel_id channel-id :guild_id guild-id}})]))
                  mentions)
          ;; Mentioned channel nodes + mentions_channel edges
          (mapcat (fn [cid]
                    (let [target-id (str "discord:channel:" cid)]
                      [(graph-node-event {:event-id (str "graph.node:" target-id)
                                          :ts ts :project project :session guild-id
                                          :node-id target-id :node-kind "discord.channel"
                                          :label (str "#" cid)
                                          :text (str "Discord channel #" cid " [" cid "]")
                                          :extra {:channel_id cid :guild_id guild-id}})
                       (graph-edge-event {:event-id (str ev-id ":mentions_channel:" target-id)
                                          :ts ts :project project :session guild-id
                                          :edge-type "mentions_channel" :layer "structural"
                                          :source-node-id ev-id :target-node-id target-id
                                          :extra {:mentioned_channel_id cid :channel_id channel-id :guild_id guild-id}})]))
                  mention-channels)
          ;; Reply edge (replies_to message + optional replies_to_user)
          (when-not (str/blank? reply-mid)
            (let [reply-target-id (str "discord:" guild-id ":" channel-id ":" reply-mid)]
              (cond-> [(graph-edge-event {:event-id (str ev-id ":replies_to:" reply-target-id)
                                          :ts ts :project project :session guild-id
                                          :edge-type "replies_to" :layer "conversational"
                                          :source-node-id ev-id :target-node-id reply-target-id
                                          :extra {:reply_to_message_id reply-mid :channel_id channel-id :guild_id guild-id}})]
                (not (str/blank? reply-aid))
                (conj (graph-edge-event {:event-id (str ev-id ":replies_to_user:discord:user:" reply-aid)
                                         :ts ts :project project :session guild-id
                                         :edge-type "replies_to_user" :layer "social"
                                         :source-node-id ev-id :target-node-id (str "discord:user:" reply-aid)
                                         :extra {:reply_to_author_id reply-aid :reply_to_message_id reply-mid}})))))
          ;; Web URL nodes + mentions_web_url edges
          (mapcat (fn [url]
                    (let [target-id (str "web:url:" url)]
                      [(graph-node-event {:event-id (str "graph.node:" target-id)
                                          :ts ts :project "web" :session guild-id
                                          :node-id target-id :node-kind "web.url"
                                          :label url
                                          :text url
                                          :extra {:url url :source_event_id ev-id}})
                       (graph-edge-event {:event-id (str ev-id ":mentions_web:" target-id)
                                          :ts ts :project project :session guild-id
                                          :edge-type "mentions_web_url" :layer "citation"
                                          :source-node-id ev-id :target-node-id target-id
                                          :source-lake project :target-lake "web"
                                          :extra {:url url :channel_id channel-id :guild_id guild-id}})]))
                  urls)))))))

(defn reaction-to-event
  "Convert a Discord reaction into an OpenPlanner event map."
  [reaction _channel guild-id emoji user-id quality]
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
