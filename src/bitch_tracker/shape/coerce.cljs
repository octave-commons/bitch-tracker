(ns bitch-tracker.shape.coerce
  "Pure, domain-agnostic structural morphisms from JavaScript objects to
  Clojure values. These functions replace the repeated str-or-aget access
  chains found in the plugin and bot socket code. No domain logic, no I/O.")

(defn- jget
  "Nullable deep property access on a JS object."
  ([obj k]
   (when (some? obj) (aget obj k)))
  ([obj k & ks]
   (reduce jget (jget obj k) ks)))

(defn message-id
  "Coerces a Discord message object's id to a string."
  [message]
  (str (or (jget message "id") "")))

(defn channel-id
  "Coerces a Discord object's channel id to a string."
  [message-or-channel]
  (str (or (jget message-or-channel "channel_id")
           (jget message-or-channel "channelId")
           "")))

(defn guild-id
  "Resolves a guild id from a payload object, optionally falling back to a
  channel object."
  ([payload]
   (str (or (jget payload "guild_id")
            (jget payload "guildId")
            "")))
  ([payload channel]
   (str (or (jget payload "guild_id")
            (jget payload "guildId")
            (jget channel "guild_id")
            (when-let [get-guild-id (jget channel "getGuildId")]
              (.call get-guild-id channel))
            ""))))

(defn emoji-str
  "Coerces a Discord emoji object to a string identifier, preferring name."
  [emoji]
  (str (or (jget emoji "name")
           (jget emoji "id")
           "")))

(defn author-id
  "Coerces a message author's id to a string."
  [message]
  (let [author (jget message "author")]
    (str (or (when (or (string? author) (number? author)) author)
             (jget author "id")
             (jget message "authorId")
             (jget message "author_id")
             (jget message "userId")
             (jget message "user_id")
             ""))))

(defn attachment->map
  "Coerces a Discord JS attachment object to a Clojure map matching the
  Attachment schema."
  [attachment]
  {:id (str (or (jget attachment "id") ""))
   :filename (or (jget attachment "filename") (jget attachment "name"))
   :content_type (or (jget attachment "content_type") (jget attachment "contentType"))
   :size (jget attachment "size")
   :url (jget attachment "url")
   :proxy_url (or (jget attachment "proxy_url") (jget attachment "proxyURL"))
   :width (jget attachment "width")
   :height (jget attachment "height")})

(defn js-seq
  "Coerces any JS iterable (array, collection, Map, plain object values) to a
  Clojure seq."
  [value]
  (cond
    (nil? value) []
    (array? value) (array-seq value)
    (jget value "toArray") (array-seq (.call (jget value "toArray") value))
    (jget value "values") (array-seq (js/Array.from (.call (jget value "values") value)))
    :else (array-seq (js/Object.values value))))
