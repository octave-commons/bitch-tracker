(ns bitch-tracker.bot.discord
  (:require [clojure.string :as str]
            [bitch-tracker.shared.util :as u]))

(def ^js discord (js/require "discord.js"))

(defn create-client [token]
  (let [^js client (new (.-Client discord)
                        #js {:intents #js [(aget (.-GatewayIntentBits discord) "Guilds")]})]
    (-> (.login client token)
        (.then (fn [_]
                 (js/console.log "[discord] Logged in as"
                                 (str (.-tag (.-user client)))
                                 (str "(" (.-id (.-user client)) ")"))
                 client)))))

(defn send-message! [^js client channel-id content]
  (let [chunks (u/discord-message-chunks content)]
    (-> (.fetch (.-channels client) channel-id)
        (.then (fn [^js channel]
                 (if channel
                   (letfn [(send-next [remaining results]
                             (if (empty? remaining)
                               (js/Promise.resolve results)
                               (-> (.send channel (first remaining))
                                   (.then (fn [msg]
                                            (send-next (rest remaining) (conj results msg))))
                                   (.catch (fn [err]
                                             (js/console.error "[discord] Send failed:" (.-message err))
                                             (send-next (rest remaining) (conj results nil)))))))]
                     (send-next (vec chunks) []))
                   (do
                     (js/console.warn "[discord] Channel" channel-id "not found")
                     (js/Promise.resolve [])))))
        (.catch (fn [err]
                  (js/console.error "[discord] Channel fetch failed:" (.-message err))
                  (js/Promise.resolve []))))))

(defn send-embed! [^js client channel-id embed-data]
  (-> (.fetch (.-channels client) channel-id)
      (.then (fn [^js channel]
               (when channel
                 (.send channel #js {:embeds #js [(clj->js embed-data)]}))))
      (.catch (fn [err]
                (js/console.error "[discord] Embed send failed:" (.-message err))))))

(defn highlight-matches [content matches]
  (let [text (str content)
        sorted (sort-by :index #(compare %2 %1) matches)]
    (reduce (fn [acc {:keys [index matched]}]
              (let [start (max 0 (min index (count acc)))
                    end (+ start (count matched))]
                (str (subs acc 0 start)
                     "**"
                     matched
                     "**"
                     (subs acc end))))
            text
            sorted)))

(defn format-tracker-message [message channel guild-id guild-name reason & [matches]]
  (let [^js author (u/jget message "author")
        aid (or (u/jget author "id") (u/jget message "author_id") "")
        content (or (u/jget message "content") "")
        message-channel-id (or (u/jget message "channel_id") (u/jget message "channelId"))
        link (str "https://discord.com/channels/" guild-id "/" message-channel-id "/" (u/jget message "id"))
        message-line (str "**Message:** "
                          (if (seq matches)
                            (u/sanitize-mentions (highlight-matches content matches))
                            (u/sanitize-mentions (or (not-empty content) "(no text content)"))))
        match-details (when (seq matches)
                        (str "**Matches:** "
                             (str/join ", "
                                       (map #(str (:label %) " (`" (:matched %) "` at index " (:index %) ")")
                                            matches))))]
    (str/join "\n" (cond-> [(str "**[" reason "]** Moderation activity detected")
                            (str "**Author:** " (u/sanitize-mentions (or (u/jget author "username") (u/jget author "globalName") "Unknown")) " (" aid ")")
                            (str "**Server:** " (or guild-name "unknown server") " (" guild-id ")")
                            (str "**Channel:** #" (or (u/jget channel "name") "unknown"))
                            (str "**Message timestamp:** " (u/discord-timestamp (or (u/jget message "timestamp") (u/jget message "timestamp" "_i"))))
                            (str "**Detected:** " (u/discord-timestamp (.getTime (js/Date.))))
                            message-line
                            (str "**Link:** " link)]
                     match-details (conj match-details)))))

(defn format-watch-message [user-id author-name count triggering-message]
  (str/join "\n" ["🐩 **Moderation Watch Alert**"
                   (str "User **" author-name "** (" user-id ") reached the reaction-label threshold.")
                   (str "Total poodle/clown labels: " count)
                   (str "Message timestamp: " (u/discord-timestamp (or (u/jget triggering-message "timestamp") (u/jget triggering-message "timestamp" "_i"))))
                   (str "Detected: " (u/discord-timestamp (.getTime (js/Date.))))
                   (str "Triggering message: " (u/sanitize-mentions (subs (str (or (u/jget triggering-message "content") "")) 0 (min 200 (count (str (or (u/jget triggering-message "content") "")))))))
                   "This user will be monitored for similar behavior patterns."]))

(defn format-similar-watch-message [user-id source-message similar-hits]
  (let [^js author (u/jget source-message "author")
        author-name (u/sanitize-mentions (or (u/jget author "username") (u/jget author "globalName") "Unknown"))
        lines (map-indexed
               (fn [idx hit]
                 (let [^js meta (or (u/jget hit "metadata") #js {})
                       distance (or (u/jget hit "distance") 0)
                       similarity (.toFixed (- 1 distance) 3)
                       text (u/sanitize-mentions (subs (str (or (u/jget hit "document") (u/jget meta "text") "")) 0 (min 150 (count (str (or (u/jget hit "document") (u/jget meta "text") ""))))))
                       source (u/sanitize-mentions (or (u/jget meta "source") "unknown"))]
                   (str (inc idx) ". [sim:" similarity "] " text " (source: " source ")")))
               (array-seq similar-hits))]
    (str/join "\n" (concat ["🔍 **Semantic Similarity Alert**"
                            (str "Moderation-watch message from **" author-name "** (" user-id "):")
                            (str "> " (subs (str (or (u/jget source-message "content") "")) 0 (min 200 (count (str (or (u/jget source-message "content") ""))))))
                            ""
                            (str "**Top " (count similar-hits) " similar messages:**")]
                           lines))))

(defn format-status-message [{:keys [status user-id username hostname socket-id slapper-role-id]}]
  (case status
    :bot-online
    (str "✅ **BitchTracker bot server is online**\n"
         "Hostname: `" hostname "`\n"
         "Started at: " (u/discord-timestamp (.getTime (js/Date.))))

    :plugin-connected
    (str "🟢 **BitchTracker plugin client connected**\n"
         "User: <@" user-id "> (" username ")\n"
         "Hostname: `" hostname "`\n"
         "Socket ID: `" socket-id "`")

    :plugin-disconnected
    (str "🔴 **BitchTracker plugin client disconnected**\n"
         "User: <@" user-id "> (" username ")\n"
         "Hostname: `" hostname "`\n"
         "Socket ID: `" socket-id "`\n"
         "<@&" slapper-role-id ">, a slapper has disconnected.")

    (str "ℹ️ BitchTracker status update")))
