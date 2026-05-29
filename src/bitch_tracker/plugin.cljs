(ns bitch-tracker.plugin
  (:require [clojure.string :as str]))

(def plugin-name "BitchTracker")
(def plugin-version "0.0.1")
(def plugin-description
  "BetterDiscord moderation/event tracker for OpenPlanner Discord ingestion, reaction labeling, and review backfill.")

(def default-base-url "http://127.0.0.1:7777")
(def default-project "discord")
(def max-batch-size 25)
(def flush-every-ms 1500)
(def retry-every-ms 10000)
(def semantic-scan-every-ms 30000)
(def max-persisted-events 500)
(def backfill-days 7)
(def backfill-batch-size 100)
(def channel-delay-ms 1500)
(def message-delay-ms 1000)
(def tracker-channel-id "1503465577132462130")
(def watch-channel-id "1503466522666995892")
(def poodle-emoji "🐩")
(def clown-emoji "🤡")
(def label-threshold 3)
(def default-semantic-query-instruction
  "Represent the Discord message for semantic retrieval of similar moderation incidents: sexist, racist, transphobic, ableist, antisemitic, misgendering, harassment, or 'just joking' bigotry. Retrieve messages with similar abusive social behavior and intent, not merely exact wording.")

(def guild-ids
  #{"1228232798448390144"
    "1391832426048651334"
    "974519864045756446"
    "1128867683291627614"
    "244230771232079873"
    "1425557239808393418"})

(def known-label-user-ids
  #{"59259128266100736"
    "376762142910578692"
    "440099490364391435"
    "1441420406711124169"
    "281812122445283330"
    "853343486756388944"})

(defn- now-iso []
  (.toISOString (js/Date.)))

(defn- global-get [k]
  (aget js/globalThis k))

(defn- present-string? [value]
  (and (string? value) (not (str/blank? value))))

(defn- meta-field [meta k fallback]
  (let [value (when meta (aget meta k))]
    (if (present-string? value) value fallback)))

(defn- jget
  ([obj k]
   (when (some? obj) (aget obj k)))
  ([obj k & ks]
   (reduce jget (jget obj k) ks)))

(defn- jcall [obj method & args]
  (when-let [f (jget obj method)]
    (.apply f obj (into-array args))))

(defn- bd-api []
  (global-get "BdApi"))

(defn- bd-webpack []
  (jget (bd-api) "Webpack"))

(defn- log! [level meta & xs]
  (let [name (meta-field meta "name" plugin-name)
        logger (jget (bd-api) "Logger")
        logger-fn (jget logger level)
        console-fn (or (jget js/console level) (.-log js/console))]
    (if logger-fn
      (.apply logger-fn logger (into-array (cons name xs)))
      (.apply console-fn js/console (into-array (cons (str "[" name "]") xs))))))

(defn- toast! [message type]
  (when-let [show-toast (jget (bd-api) "UI" "showToast")]
    (.call show-toast (jget (bd-api) "UI") message #js {:type type})))

(defn ^:async sleep-ms [ms]
  (await
   (js/Promise.
    (fn [resolve _reject]
      (js/setTimeout resolve ms)))))

(defn- make-state []
  #js {:started false
       :startedAt nil
       :stoppedAt nil
       :dispatcher nil
       :channelStore nil
       :guildStore nil
       :userStore nil
       :messageStore nil
       :queue #js []
       :seen (js/Set.)
       :flushTimer nil
       :retryTimer nil
       :semanticScanTimer nil
       :bitchCounts (js/Map.)
       :bitchMessages (js/Map.)
       :labeledMessages (js/Set.)
       :pendingSemanticQueries (js/Set.)
       :runningBackfill false})

(defn- state-get [state k]
  (aget state k))

(defn- state-set! [state k v]
  (aset state k v)
  v)

(defn- js-values [value]
  (cond
    (nil? value) []
    (array? value) (array-seq value)
    (jget value "toArray") (array-seq (jcall value "toArray"))
    (jget value "values") (array-seq (js/Array.from (jcall value "values")))
    :else (array-seq (js/Object.values value))))

(defn- bd-data-load [plugin key]
  (jcall (jget (bd-api) "Data") "load" plugin key))

(defn- bd-data-save! [plugin key value]
  (jcall (jget (bd-api) "Data") "save" plugin key value))

(defn- setting [plugin key fallback]
  (let [value (bd-data-load plugin key)]
    (if (nil? value) fallback value)))

(defn- save-setting! [plugin key value]
  (bd-data-save! plugin key value))

(defn- node-require [module-name]
  (try
    (let [req (global-get "require")]
      (when req (.call req nil module-name)))
    (catch :default _err nil)))

(defn- path-join [& pieces]
  (if-let [path (node-require "path")]
    (.apply (jget path "join") path (into-array pieces))
    (str/join "/" pieces)))

(defn- file-exists? [file]
  (try
    (boolean (jcall (node-require "fs") "existsSync" file))
    (catch :default _err false)))

(defn- read-file [file]
  (try
    (jcall (node-require "fs") "readFileSync" file "utf8")
    (catch :default _err nil)))

(defn- plugin-file-candidates [filename]
  (let [dirname (global-get "__dirname")
        plugins-folder (jget (bd-api) "Plugins" "folder")
        home (or (jget (global-get "process") "env" "HOME") "/home/err")]
    (->> [(when (present-string? dirname) (path-join dirname filename))
          (when (present-string? plugins-folder) (path-join plugins-folder filename))
          (path-join home "snap/discord/current/.config/BetterDiscord/plugins" filename)
          (path-join home ".config/BetterDiscord/plugins" filename)]
         (remove str/blank?)
         distinct
         vec)))

(defn- plugin-file-path [filename]
  (or (some #(when (file-exists? %) %) (plugin-file-candidates filename))
      (first (plugin-file-candidates filename))
      ""))

(defn- parse-json [raw fallback]
  (try
    (if (present-string? raw) (js/JSON.parse raw) fallback)
    (catch :default _err fallback)))

(defn- json-field-string [raw key parsed-value]
  (let [pattern (js/RegExp. (str "\"" key "\"\\s*:\\s*(\"(?:\\\\.|[^\"\\\\])*\"|[0-9]+)"))
        match (.match (str raw) pattern)]
    (if match
      (let [value (aget match 1)]
        (if (str/starts-with? value "\"")
          (try (str (js/JSON.parse value))
               (catch :default _err (subs value 1 (dec (count value)))))
          value))
      (if (nil? parsed-value) "" (str parsed-value)))))

(defn- bot-config []
  (try
    (let [file (plugin-file-path "bot.json")
          raw (when (and (present-string? file) (file-exists? file)) (read-file file))
          parsed (parse-json raw nil)
          token (str/trim (str (or (jget parsed "token") (json-field-string raw "token" ""))))]
      (when (and (present-string? token) (not= token "<bot-token>"))
        #js {:username (str/trim (str (or (jget parsed "username") "")))
             :token token
             :appId (json-field-string raw "appId" (jget parsed "appId"))
             :botUserId (json-field-string raw "botUserId" (jget parsed "botUserId"))
             :botServerAliasName (str/trim (str (or (jget parsed "botServerAliasName") "")))}))
    (catch :default err
      (log! "warn" nil "Failed to read bot.json" err)
      nil)))

(defn- bot-user-id []
  (or (jget (bot-config) "botUserId") ""))

(defn- bot-auth-header [token]
  (let [value (str/trim (str token))]
    (if (.test (js/RegExp. "^Bot\\s+" "i") value)
      value
      (str "Bot " value))))

(defn- auth-header [api-key-or-header]
  (let [value (str/trim (str api-key-or-header))]
    (if (.test (js/RegExp. "^Bearer\\s+" "i") value)
      value
      (str "Bearer " value))))

(defn- endpoint []
  (let [explicit (str/trim (str (setting "OpenPlannerEventIngest" "endpoint" "")))]
    (if (present-string? explicit)
      explicit
      (str (str/replace (or (jget (global-get "process") "env" "OPENPLANNER_BASE_URL") default-base-url)
                        #"/+$" "")
           "/v1/events"))))

(defn- api-key []
  (str/trim (str (or (setting "OpenPlannerEventIngest" "apiKey" "")
                     (jget (global-get "process") "env" "OPENPLANNER_API_KEY")
                     ""))))

(defn- discord-timestamp [value]
  (let [date (if value (js/Date. value) (js/Date.))
        ms (if (js/isNaN (.getTime date)) (.getTime (js/Date.)) (.getTime date))]
    (str "<t:" (js/Math.floor (/ ms 1000)) ":F>")))

(defn- nonce []
  (str (.getTime (js/Date.)) "-" (subs (.toString (js/Math.random) 36) 2)))

(defn- sanitize-mentions [text]
  (-> (str text)
      (str/replace "@everyone" "@\u200beveryone")
      (str/replace "@here" "@\u200bhere")
      (str/replace #"<@!?([0-9]+)>" "<@\u200b$1>")
      (str/replace #"<@&([0-9]+)>" "<@&\u200b$1>")
      (str/replace #"<#([0-9]+)>" "<#\u200b$1>")))

(defn- escape-regexp [value]
  (.replace (str value) (js/RegExp. "[.*+?^${}()|[\\]\\\\]" "g") "\\$&"))

(defn- watch-config []
  (try
    (let [file (plugin-file-path "OpenPlannerModerationWatchlist.json")]
      (if (and (present-string? file) (file-exists? file))
        (parse-json (read-file file) #js {})
        #js {}))
    (catch :default err
      (log! "warn" nil "Failed to read moderation watchlist config" err)
      #js {})))

(defn- watch-terms []
  (let [settings-terms (->> (str/split (str (setting "OpenPlannerEventIngest" "watchTerms" "")) #",")
                            (map str/trim)
                            (remove str/blank?))
        config (watch-config)
        cfg-terms (js-values (jget config "watchTerms"))
        cfg-regexes (js-values (jget config "watchRegexes"))]
    (->> (concat settings-terms cfg-terms cfg-regexes)
         (map #(str/trim (str %)))
         (remove str/blank?)
         distinct
         vec)))

(defn- watch-pattern [term]
  (let [match (.match (str term) (js/RegExp. "^/(.*)/([a-z]*)$" "i"))]
    (if match
      (try
        (let [body (aget match 1)
              flags (aget match 2)
              final-flags (if (str/includes? flags "i") flags (str flags "i"))]
          #js {:label term :pattern (js/RegExp. body final-flags)})
        (catch :default _err nil))
      #js {:label term :pattern (js/RegExp. (str "\\b" (escape-regexp term) "\\b") "i")})))

(defn- moderation-hits [text]
  (let [value (str text)]
    (if (str/blank? value)
      []
      (->> (watch-terms)
           (map watch-pattern)
           (remove nil?)
           (filter #(.test (jget % "pattern") value))
           (map #(jget % "label"))
           vec))))

(defn- ids [value]
  (->> (js-values value)
       (map #(str (or (jget % "id") %)))
       (remove str/blank?)
       vec))

(defn- author-id [message]
  (let [author (jget message "author")]
    (str (or (when (or (string? author) (number? author)) author)
             (jget author "id")
             (jget message "authorId")
             (jget message "author_id")
             (jget message "userId")
             (jget message "user_id")
             ""))))

(defn- quality-from-emoji [emoji]
  (case (str emoji)
    ("✅" "☑️" "✔️" "✔") "good"
    ("❌" "✖️" "✖" "❎") "bad"
    nil))

(defn- reaction-label [emoji]
  (let [value (str/trim (str emoji))
        custom-match (.match value (js/RegExp. "^<a?:([^:>]+):([0-9]+)>$"))]
    (cond
      (str/blank? value) "unknown"
      custom-match (str "custom:" (aget custom-match 1) ":" (aget custom-match 2))
      (.test (js/RegExp. "^[0-9]+$") value) (str "custom:" value)
      :else (str "unicode:" (str/join "-" (map #(.toString (.codePointAt % 0) 16) (array-seq (js/Array.from value))))))))

(defn- label-emoji? ([emoji] (label-emoji? emoji emoji))
  ([emoji-name emoji]
   (some #(or (= (str emoji-name) %) (= (str emoji) %)) [poodle-emoji clown-emoji])))

(defn- load-modules! [state]
  (let [webpack (bd-webpack)
        get-module (jget webpack "getModule")
        get-store (jget webpack "getStore")
        dispatcher (when get-module (.call get-module webpack (fn [m] (and (jget m "dispatch") (jget m "subscribe") (jget m "unsubscribe"))) #js {:searchExports true}))
        channel-store (or (when get-store (.call get-store webpack "ChannelStore"))
                          (when get-module (.call get-module webpack (fn [m] (and (jget m "getChannel") (jget m "getDMFromUserId"))) #js {:searchExports true})))
        guild-store (or (when get-store (.call get-store webpack "GuildStore"))
                        (when get-module (.call get-module webpack (fn [m] (and (jget m "getGuild") (jget m "getGuilds"))) #js {:searchExports true})))
        user-store (or (when get-store (.call get-store webpack "UserStore"))
                       (when get-module (.call get-module webpack (fn [m] (and (jget m "getCurrentUser") (jget m "getUser"))) #js {:searchExports true})))
        message-store (or (when get-store (.call get-store webpack "MessageStore"))
                          (when get-module (.call get-module webpack (fn [m] (and (jget m "getMessage") (jget m "getMessages"))) #js {:searchExports true})))]
    (state-set! state "dispatcher" dispatcher)
    (state-set! state "channelStore" channel-store)
    (state-set! state "guildStore" guild-store)
    (state-set! state "userStore" user-store)
    (state-set! state "messageStore" message-store)
    state))

(declare flush! send-discord-message! send-to-tracker! send-to-watch! run-semantic-scan! run-backfill! message-to-event reaction-to-event)

(defn- seen-add! [state id]
  (let [seen (state-get state "seen")]
    (if (.has seen id)
      false
      (do (.add seen id) true))))

(defn- queue-event! [state event]
  (when (and event (seen-add! state (jget event "id")))
    (.push (state-get state "queue") event)
    (bd-data-save! "OpenPlannerEventIngest" "queue" (.slice (state-get state "queue") (- max-persisted-events)))
    (when (>= (.-length (state-get state "queue")) max-batch-size)
      (flush! state)))
  event)

(defn- persist-label-state! [state]
  (let [counts (js/Array.from (.entries (state-get state "bitchCounts")))
        messages (.map (js/Array.from (.entries (state-get state "bitchMessages")))
                       (fn [entry]
                         #js [(aget entry 0) (js/Array.from (aget entry 1))]))
        labeled (js/Array.from (state-get state "labeledMessages"))
        pending (js/Array.from (state-get state "pendingSemanticQueries"))]
    (bd-data-save! "OpenPlannerEventIngest" "bitchState"
                   #js {:counts counts :messages messages :labeled labeled :pendingSemantic pending})))

(defn- load-label-state! [state]
  (when-let [saved (bd-data-load "OpenPlannerEventIngest" "bitchState")]
    (when (jget saved "counts") (state-set! state "bitchCounts" (js/Map. (jget saved "counts"))))
    (when (jget saved "messages")
      (state-set! state "bitchMessages"
                  (js/Map. (.map (jget saved "messages") (fn [entry] #js [(aget entry 0) (js/Set. (aget entry 1))])))))
    (when (jget saved "labeled") (state-set! state "labeledMessages" (js/Set. (jget saved "labeled"))))
    (when (jget saved "pendingSemantic") (state-set! state "pendingSemanticQueries" (js/Set. (jget saved "pendingSemantic"))))))

(defn- load-persisted-queue! [state]
  (when-let [persisted (bd-data-load "OpenPlannerEventIngest" "queue")]
    (when (array? persisted)
      (let [queue (.slice persisted (- max-persisted-events))]
        (state-set! state "queue" queue)
        (doseq [event (array-seq queue)]
          (when-let [id (jget event "id")]
            (.add (state-get state "seen") id)))))))

(defn- guild-id-for [_state message channel fallback]
  (str (or fallback
           (jget message "guild_id")
           (jget message "guildId")
           (jget channel "guild_id")
           (jcall channel "getGuildId")
           "")))

(defn- channel-for [state channel-id]
  (jcall (state-get state "channelStore") "getChannel" channel-id))

(defn- on-message-create! [state payload]
  (let [message (or (jget payload "message") payload)
        message-id (jget message "id")
        channel-id (or (jget message "channel_id") (jget message "channelId"))]
    (when (and message-id channel-id (not= (jget message "state") "SENDING") (not= (jget message "type") 8))
      (let [channel (channel-for state channel-id)
            guild-id (guild-id-for state message channel nil)
            author (jget message "author")
            aid (str (or (jget author "id") (jget message "author_id") ""))
            content (if (string? (jget message "content")) (jget message "content") "")]
        (when (and (contains? guild-ids guild-id) (not= aid (bot-user-id)))
          (queue-event! state (message-to-event state message channel guild-id))
          (when (contains? known-label-user-ids aid)
            (send-to-tracker! state message channel guild-id "known-watch-user")
            (when-not (str/blank? content)
              (js/setTimeout #(send-to-watch! state aid message #js []) 5000)))
          (let [hits (moderation-hits content)]
            (when (seq hits)
              (send-to-tracker! state message channel guild-id (str "moderation-watch: " (str/join ", " hits))))))))))

(defn- send-similar-to-watch! [state user-id source-message similar-hits]
  (when (and watch-channel-id (> (.-length similar-hits) 0))
    (let [author (jget source-message "author")
          author-name (sanitize-mentions (or (jget author "username") (jget author "globalName") "Unknown"))
          lines (map-indexed
                 (fn [idx hit]
                   (let [meta (or (jget hit "metadata") #js {})
                         distance (or (jget hit "distance") 0)
                         similarity (.toFixed (- 1 distance) 3)
                         text (sanitize-mentions (subs (str (or (jget hit "document") (jget meta "text") "")) 0 (min 150 (count (str (or (jget hit "document") (jget meta "text") ""))))))
                         source (sanitize-mentions (or (jget meta "source") "unknown"))]
                     (str (inc idx) ". [sim:" similarity "] " text " (source: " source ")")))
                 (array-seq similar-hits))
          content (str/join "\n" (concat ["🔍 **Semantic Similarity Alert**"
                                            (str "Moderation-watch message from **" author-name "** (" user-id "):")
                                            (str "> " (subs (str (or (jget source-message "content") "")) 0 (min 200 (count (str (or (jget source-message "content") ""))))))
                                            ""
                                            (str "**Top " (.-length similar-hits) " similar messages:**")]
                                           lines))]
      (send-discord-message! state watch-channel-id content))))

(defn ^:async query-semantic-similar [text k]
  (let [api-key-value (api-key)
        query-endpoint (str/replace (endpoint) "/v1/events" "/v1/graph/similar")]
    (if (or (str/blank? api-key-value) (str/blank? query-endpoint))
      #js []
      (try
        (let [res (await (js/fetch query-endpoint
                                   #js {:method "POST"
                                        :headers #js {"content-type" "application/json"
                                                      "authorization" (auth-header api-key-value)}
                                        :body (js/JSON.stringify
                                               #js {:q (let [instruction (str/trim (str (setting "OpenPlannerEventIngest" "semanticQueryInstruction" default-semantic-query-instruction)))
                                                          message (str/trim (str text))]
                                                      (if (str/blank? instruction) message (str instruction "\n\nDiscord message:\n" message)))
                                                    :k k
                                                    :where #js {:source "betterdiscord-openplanner"}})}))
              data (if (jget res "ok") (await (.json res)) nil)
              hits (or (jget data "hits") #js [])]
          (.filter hits (fn [hit]
                          (>= (- 1 (or (jget hit "distance") 0)) 0.75))))
        (catch :default err
          (log! "warn" nil "Semantic query failed" err)
          #js [])))))

(defn- queue-semantic-search! [state user-id message delay-ms k]
  (js/setTimeout
   (fn []
     (-> (query-semantic-similar (jget message "content") k)
         (.then (fn [similar]
                  (when (> (.-length similar) 0)
                    (send-similar-to-watch! state user-id message similar))))))
   delay-ms))

(defn- handle-label-reaction! [state message-id channel-id _reactor-user-id]
  (let [message (jcall (state-get state "messageStore") "getMessage" channel-id message-id)]
    (if-not message
      (log! "warn" nil "Could not find labeled message" message-id "in" channel-id)
      (let [author (jget message "author")
            aid (str (or (jget author "id") ""))]
        (when-not (str/blank? aid)
          (let [messages (state-get state "bitchMessages")
                counts (state-get state "bitchCounts")
                labeled (state-get state "labeledMessages")]
            (when-not (.has messages aid) (.set messages aid (js/Set.)))
            (.add (.get messages aid) message-id)
            (.add labeled message-id)
            (let [current (or (.get counts aid) 0)
                  next-count (inc current)]
              (.set counts aid next-count)
              (send-to-tracker! state message (channel-for state channel-id) nil "poodle-labeled")
              (when (and (>= next-count label-threshold) (< current label-threshold))
                (send-to-watch! state aid message nil)
                (toast! (str "User tagged for moderation watch: " aid) "warning"))
              (.add (state-get state "pendingSemanticQueries") message-id)
              (when (present-string? (jget message "content"))
                (queue-semantic-search! state aid message 100 5))
              (persist-label-state! state))))))))

(defn- handle-label-reaction-remove! [state message-id channel-id]
  (let [message (jcall (state-get state "messageStore") "getMessage" channel-id message-id)
        aid (author-id message)
        labeled (state-get state "labeledMessages")]
    (when (and message (present-string? aid) (.has labeled message-id))
      (.delete labeled message-id)
      (when-let [messages (.get (state-get state "bitchMessages") aid)]
        (.delete messages message-id))
      (let [counts (state-get state "bitchCounts")
            current (or (.get counts aid) 0)]
        (when (> current 0) (.set counts aid (dec current))))
      (persist-label-state! state))))

(defn- on-reaction-add! [state payload]
  (let [message-id (str (or (jget payload "messageId") ""))
        channel-id (str (or (jget payload "channelId") ""))]
    (when (and (present-string? message-id) (present-string? channel-id))
      (let [channel (channel-for state channel-id)
            guild-id (str (or (jget payload "guildId") (jget channel "guild_id") (jcall channel "getGuildId") ""))
            emoji-name (str (or (jget payload "emoji" "name") ""))
            emoji-id (str (or (jget payload "emoji" "id") ""))
            emoji (if (present-string? emoji-name) emoji-name emoji-id)
            user-id (str (or (jget payload "userId") ""))]
        (when (contains? guild-ids guild-id)
          (when (label-emoji? emoji-name emoji)
            (handle-label-reaction! state message-id channel-id user-id))
          (when-let [quality (quality-from-emoji emoji)]
            (let [message (jcall (state-get state "messageStore") "getMessage" channel-id message-id)
                  event (reaction-to-event state payload channel guild-id emoji user-id quality)]
              (when message (queue-event! state event))))
          (queue-event! state (reaction-to-event state payload channel guild-id emoji user-id nil)))))))

(defn- on-reaction-remove! [state payload]
  (let [message-id (str (or (jget payload "messageId") ""))
        channel-id (str (or (jget payload "channelId") ""))
        channel (channel-for state channel-id)
        guild-id (str (or (jget payload "guildId") (jget channel "guild_id") (jcall channel "getGuildId") ""))
        emoji-name (str (or (jget payload "emoji" "name") ""))
        emoji-id (str (or (jget payload "emoji" "id") ""))
        emoji (if (present-string? emoji-name) emoji-name emoji-id)]
    (when (and (contains? guild-ids guild-id) (label-emoji? emoji-name emoji))
      (handle-label-reaction-remove! state message-id channel-id))))

(defn message-to-event [state message channel guild-id]
  (let [guild (jcall (state-get state "guildStore") "getGuild" guild-id)
        author (or (jget message "author") (jcall (state-get state "userStore") "getUser" (jget message "author_id")))
        aid (str (or (jget author "id") (jget message "author_id") "unknown"))
        content (if (string? (jget message "content")) (jget message "content") "")
        attachments (map (fn [a]
                           {:id (str (or (jget a "id") ""))
                            :filename (or (jget a "filename") (jget a "name"))
                            :content_type (or (jget a "content_type") (jget a "contentType"))
                            :size (jget a "size")
                            :url (jget a "url")
                            :proxy_url (or (jget a "proxy_url") (jget a "proxyURL"))
                            :width (jget a "width")
                            :height (jget a "height")})
                         (js-values (jget message "attachments")))
        embeds (map (fn [e]
                      {:type (jget e "type")
                       :title (jget e "title")
                       :description (jget e "description")
                       :url (jget e "url")
                       :provider (jget e "provider" "name")})
                    (js-values (jget message "embeds")))
        ts (.toISOString (js/Date. (or (jget message "timestamp") (jget message "timestamp" "_i") (.getTime (js/Date.)))))
        hits (moderation-hits content)
        labels (cond-> []
                 (contains? known-label-user-ids aid) (conj "moderation-watch:known-user" (str "moderation-watch:user:" aid))
                 (seq hits) (into (cons "moderation-watch:term" (map #(str "moderation-watch:" %) hits))))
        event {:schema "openplanner.event.v1"
               :schema_version 1
               :id (str "discord:" guild-id ":" (or (jget message "channel_id") (jget message "channelId")) ":" (jget message "id"))
               :ts ts
               :source "betterdiscord-openplanner"
               :kind "discord.message"
               :source_ref {:project (setting "OpenPlannerEventIngest" "project" default-project)
                            :session guild-id
                            :message (str (jget message "id"))}
               :text (or (not-empty content)
                         (not-empty (str/join "\n" (keep :url attachments)))
                         (not-empty (str/join "\n" (keep :url embeds)))
                         "")
               :meta {:author (or (jget author "username") (jget author "globalName") aid)
                      :author_id aid
                      :author_username (jget author "username")
                      :author_global_name (jget author "globalName")
                      :bot (boolean (jget author "bot"))
                      :tags (cond-> ["discord" "message"]
                              (contains? known-label-user-ids aid) (into ["known-watch-user" "moderation-watch"])
                              (seq hits) (conj "moderation-watch"))}
               :extra (cond-> {:guild_id guild-id
                                :guild_name (jget guild "name")
                                :channel_id (str (or (jget message "channel_id") (jget message "channelId")))
                                :channel_name (jget channel "name")
                                :message_id (str (jget message "id"))
                                :nonce (jget message "nonce")
                                :type (jget message "type")
                                :flags (jget message "flags")
                                :pinned (boolean (jget message "pinned"))
                                :tts (boolean (jget message "tts"))
                                :mention_everyone (boolean (jget message "mention_everyone"))
                                :mentions (ids (jget message "mentions"))
                                :mention_roles (ids (jget message "mention_roles"))
                                :attachments attachments
                                :embeds embeds
                                :edited_timestamp (jget message "edited_timestamp")}
                        (seq labels) (assoc :openplanner_labels {:claim_system "discord-moderation-watch-v1"
                                                                  :labels (vec (distinct labels))
                                                                  :updated_at (now-iso)})
                        (contains? known-label-user-ids aid) (assoc :is_known_watch_user true)
                        (seq hits) (assoc :moderation_watch_hits hits))}]
    (clj->js event)))

(defn reaction-to-event [_state reaction _channel guild-id emoji user-id quality]
  (let [message-id (str (or (jget reaction "messageId") ""))
        channel-id (str (or (jget reaction "channelId") ""))
        base-label (reaction-label emoji)
        label? (label-emoji? emoji)
        quality-value (or quality (quality-from-emoji emoji))
        labels (cond-> [(str "reaction:" base-label)]
                 quality-value (conj (str "quality:" quality-value))
                 label? (into ["moderation:poodle" "moderation-watch:poodle-label"]))]
    (when (and (present-string? message-id) (present-string? channel-id))
      (clj->js {:schema "openplanner.event.v1"
                :schema_version 1
                :id (str "discord:reaction:" guild-id ":" channel-id ":" message-id ":" emoji ":" user-id)
                :ts (now-iso)
                :source "betterdiscord-openplanner"
                :kind "discord.reaction"
                :source_ref {:project (setting "OpenPlannerEventIngest" "project" default-project)
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
                                                      :updated_at (now-iso)}}
                         quality-value (assoc :quality quality-value))}))))

(defn- discord-message-chunks [content]
  (let [text (str content)]
    (if (<= (count text) 1900)
      [(if (str/blank? text) "(empty message)" text)]
      (loop [chunks [] rest text]
        (if (<= (count rest) 1900)
          (let [all (cond-> chunks (not (str/blank? rest)) (conj rest))]
            (map-indexed #(if (= 1 (count all)) %2 (str "(" (inc %1) "/" (count all) ")\n" %2)) all))
          (let [newline-index (.lastIndexOf rest "\n" 1900)
                split-at (if (< newline-index 500) 1900 newline-index)]
            (recur (conj chunks (subs rest 0 split-at))
                   (str/replace (subs rest split-at) #"^\n+" ""))))))))

(defn ^:async send-via-bot-token! [channel-id body label]
  (let [config (bot-config)]
    (if-not (jget config "token")
      (do (log! "warn" nil (str "[" label "] bot.json missing or invalid; protected send skipped")) false)
      (let [fetch-impl (or (jget (bd-api) "Net" "fetch") js/fetch)
            url (str "https://discord.com/api/v10/channels/" channel-id "/messages")
            headers #js {:Authorization (bot-auth-header (jget config "token"))
                         :Content-Type "application/json"}]
        (try
          (let [res (await (.call fetch-impl nil url #js {:method "POST" :headers headers :body (js/JSON.stringify body)}))]
            (if (jget res "ok")
              true
              (do (log! "warn" nil (str "[" label "] bot send failed HTTP " (jget res "status"))) false)))
          (catch :default err
            (log! "warn" nil (str "[" label "] bot send failed") err)
            false))))))

(defn ^:async send-via-discord-http! [channel-id body label]
  (let [bd (bd-api)
        webpack (bd-webpack)
        http (or (jcall bd "findModuleByProps" "get" "post" "put" "del")
                 (when-let [get-module (jget webpack "getModule")]
                   (or (.call get-module webpack (fn [m] (and (jget m "post") (jget m "get") (fn? (jget m "post")))) #js {:searchExports true})
                       (jget (.call get-module webpack (fn [m] (and (jget m "HTTP" "post") (fn? (jget m "HTTP" "post")))) #js {:searchExports true}) "HTTP"))))
        endpoints (or (jget (jcall bd "findModuleByProps" "Endpoints") "Endpoints")
                      (when-let [get-module (jget webpack "getModule")]
                        (jget (.call get-module webpack (fn [m] (jget m "Endpoints" "MESSAGES")) #js {:searchExports true}) "Endpoints")))
        url (if (jget endpoints "MESSAGES") (jcall endpoints "MESSAGES" channel-id) (str "/channels/" channel-id "/messages"))]
    (if-not (jget http "post")
      false
      (try
        (await (jcall http "post" #js {:url url :body body}))
        true
        (catch :default err
          (log! "warn" nil (str "[" label "] HTTP module send failed") err)
          false)))))

(defn ^:async send-via-discord-fetch! [channel-id body label]
  (let [bd (bd-api)
        webpack (bd-webpack)
        auth-module (or (jcall bd "findModuleByProps" "getToken")
                        (when-let [get-module (jget webpack "getModule")]
                          (.call get-module webpack (fn [m] (and (jget m "getToken") (fn? (jget m "getToken")))) #js {:searchExports true})))
        token (jcall auth-module "getToken")]
    (if-not token
      false
      (try
        (let [fetch-impl (or (jget bd "Net" "fetch") js/fetch)
              res (await (.call fetch-impl nil (str "https://discord.com/api/v9/channels/" channel-id "/messages")
                                #js {:method "POST"
                                     :headers #js {:Authorization token :Content-Type "application/json"}
                                     :body (js/JSON.stringify body)}))]
          (boolean (jget res "ok")))
        (catch :default err
          (log! "warn" nil (str "[" label "] fetch send failed") err)
          false)))))

(defn ^:async send-via-message-actions! [channel-id body label]
  (let [bd (bd-api)
        webpack (bd-webpack)
        actions (or (jcall bd "findModuleByProps" "sendMessage" "editMessage" "deleteMessage")
                    (when-let [get-module (jget webpack "getModule")]
                      (.call get-module webpack (fn [m] (and (jget m "sendMessage") (fn? (jget m "sendMessage")))) #js {:searchExports true})))]
    (if-not (jget actions "sendMessage")
      false
      (try
        (await (jcall actions "sendMessage" channel-id body nil #js {:nonce (jget body "nonce")}))
        true
        (catch :default err
          (log! "warn" nil (str "[" label "] MessageActions send failed") err)
          false)))))

(defn ^:async send-discord-message! [_state channel-id content]
  (doseq [chunk (discord-message-chunks content)]
    (let [body #js {:content chunk :nonce (nonce) :tts false :allowed_mentions #js {:parse #js []}}
          protected? (some #(= (str %) (str channel-id)) [tracker-channel-id watch-channel-id])]
      (if protected?
        (let [ok? (await (send-via-bot-token! channel-id body "BitchTracker"))]
          (when-not ok?
            (log! "error" nil "Protected-channel send failed via bot token; not falling back to user auth")))
        (let [ok? (or (await (send-via-discord-http! channel-id body "BitchTracker"))
                      (await (send-via-discord-fetch! channel-id body "BitchTracker"))
                      (await (send-via-message-actions! channel-id body "BitchTracker")))]
          (when-not ok?
            (log! "error" nil "Failed to send Discord message chunk: no send path succeeded")))))))

(defn- send-to-tracker! [state message channel guild-id reason]
  (when tracker-channel-id
    (let [message-channel-id (or (jget message "channel_id") (jget message "channelId"))]
      (when-not (= (str message-channel-id) tracker-channel-id)
        (let [content (or (jget message "content") "")
              author (jget message "author")
              aid (author-id message)
              final-guild-id (or guild-id (jget channel "guild_id") "@me")
              guild (when-not (= final-guild-id "@me") (jcall (state-get state "guildStore") "getGuild" final-guild-id))
              guild-name (or (jget guild "name") (jget channel "guild" "name") (jget channel "guildName") "unknown server")
              link (str "https://discord.com/channels/" final-guild-id "/" message-channel-id "/" (jget message "id"))
              hits (moderation-hits content)
              tracker-message (str/join "\n" (cond-> [(str "**[" reason "]** Moderation activity detected")
                                                        (str "**Author:** " (sanitize-mentions (or (jget author "username") (jget author "globalName") "Unknown")) " (" aid ")")
                                                        (str "**Server:** " guild-name " (" final-guild-id ")")
                                                        (str "**Channel:** #" (or (jget channel "name") "unknown"))
                                                        (str "**Message timestamp:** " (discord-timestamp (or (jget message "timestamp") (jget message "timestamp" "_i"))))
                                                        (str "**Detected:** " (discord-timestamp (.getTime (js/Date.))))]
                                                 (seq hits) (conj (str "**Matched watch terms:** " (str/join ", " hits)))
                                                 true (conj (str "**Message:** " (sanitize-mentions (or (not-empty content) "(no text content)")))
                                                            (str "**Link:** " link))))]
          (send-discord-message! state tracker-channel-id tracker-message))))))

(defn- send-to-watch! [state user-id triggering-message _similar]
  (when watch-channel-id
    (let [author (jget triggering-message "author")
          author-name (sanitize-mentions (or (jget author "username") (jget author "globalName") "Unknown"))
          count (or (.get (state-get state "bitchCounts") user-id) 0)
          watch-message (str/join "\n" ["🐩 **Moderation Watch Alert**"
                                         (str "User **" author-name "** (" user-id ") reached the reaction-label threshold.")
                                         (str "Total poodle/clown labels: " count)
                                         (str "Message timestamp: " (discord-timestamp (or (jget triggering-message "timestamp") (jget triggering-message "timestamp" "_i"))))
                                         (str "Detected: " (discord-timestamp (.getTime (js/Date.))))
                                         (str "Triggering message: " (sanitize-mentions (subs (str (or (jget triggering-message "content") "")) 0 (min 200 (count (str (or (jget triggering-message "content") "")))))))
                                         "This user will be monitored for similar behavior patterns."])]
      (send-discord-message! state watch-channel-id watch-message))))

(defn ^:async flush! [state]
  (let [queue (state-get state "queue")
        api-key-value (api-key)]
    (when (and (> (.-length queue) 0) (present-string? api-key-value))
      (let [batch (.slice queue 0 max-batch-size)]
        (try
          (let [res (await (js/fetch (endpoint)
                                     #js {:method "POST"
                                          :headers #js {"content-type" "application/json"
                                                        "authorization" (auth-header api-key-value)}
                                          :body (js/JSON.stringify #js {:events batch})}))]
            (when-not (jget res "ok")
              (throw (js/Error. (str (jget res "status") " " (jget res "statusText")))))
            (.splice queue 0 (.-length batch))
            (bd-data-save! "OpenPlannerEventIngest" "queue" (.slice queue (- max-persisted-events)))
            (log! "info" nil "Sent event batch" (.-length batch)))
          (catch :default err
            (log! "warn" nil "Flush failed; will retry" err)))))))

(defn ^:async run-semantic-scan! [state]
  (let [pending (state-get state "pendingSemanticQueries")]
    (when (> (.-size pending) 0)
      (let [api-key-value (api-key)
            base-url (str/replace (endpoint) "/v1/events" "")]
        (when (and (present-string? api-key-value) (present-string? base-url))
          (try
            (let [to-check (.slice (js/Array.from pending) 0 10)
                  event-ids (.map to-check (fn [id] (str "discord:discord.message:" id)))
                  res (await (js/fetch (str base-url "/v1/graph/node-embeddings/query")
                                       #js {:method "POST"
                                            :headers #js {"content-type" "application/json"
                                                          "authorization" (auth-header api-key-value)}
                                            :body (js/JSON.stringify #js {:eventIds event-ids})}))
                  data (when (jget res "ok") (await (.json res)))
                  embedded (js/Set. (.map (or (jget data "vectors") #js []) (fn [v] (or (jget v "sourceEventId") (jget v "id")))))]
              (doseq [message-id (array-seq to-check)]
                (let [event-id (str "discord:discord.message:" message-id)]
                  (when (.has embedded event-id)
                    (.delete pending message-id)))))
            (catch :default err
              (log! "warn" nil "Semantic scan failed" err))))))))

(defn- has-label-reaction? [message]
  (some (fn [reaction]
          (let [emoji (jget reaction "emoji")]
            (or (label-emoji? emoji)
                (label-emoji? (jget emoji "name"))
                (label-emoji? (jget reaction "emojiName"))
                (label-emoji? (jget reaction "name")))))
        (js-values (jget message "reactions"))))

(defn- relevant-channels [state]
  (let [channels (array)]
    (doseq [guild-id (js/Object.keys (or (jcall (state-get state "guildStore") "getGuilds") #js {}))]
      (when-let [guild-channels (jcall (state-get state "channelStore") "getMutableGuildChannelsForGuild" guild-id)]
        (doseq [channel (js-values guild-channels)]
          (when (= (jget channel "type") 0)
            (.push channels channel)))))
    (doseq [channel (js-values (or (jcall (state-get state "channelStore") "getMutablePrivateChannels") #js {}))]
      (.push channels channel))
    channels))

(defn ^:async fetch-messages-from-channel [state channel-id since]
  (try
    (let [webpack (bd-webpack)
          message-actions (when-let [get-module (jget webpack "getModule")]
                            (.call get-module webpack (fn [m] (or (jget m "fetchMessages") (jget m "loadMessages"))) #js {:searchExports true}))]
      (when (jget message-actions "fetchMessages")
        (try (await (jcall message-actions "fetchMessages" #js {:channelId channel-id :limit backfill-batch-size}))
             (catch :default err (log! "warn" nil "fetchMessages failed" err))))
      (let [messages (jcall (state-get state "messageStore") "getMessages" channel-id)
            arr (js-values messages)]
        (clj->js (filter (fn [message]
                           (let [ts (.getTime (js/Date. (or (jget message "timestamp") 0)))]
                             (>= ts since)))
                         arr))))
    (catch :default err
      (log! "warn" nil "Failed to fetch messages for backfill" channel-id err)
      #js [])))

(defn ^:async run-backfill! [state]
  (if (state-get state "runningBackfill")
    (toast! "Backfill already running" "warning")
    (do
      (state-set! state "runningBackfill" true)
      (toast! "Starting moderation backfill..." "info")
      (try
        (let [since (- (.getTime (js/Date.)) (* backfill-days 24 60 60 1000))
              channels (relevant-channels state)
              total-sent (atom 0)
              total-found (atom 0)]
          (doseq [channel (array-seq channels)]
            (when (state-get state "runningBackfill")
              (let [messages (await (fetch-messages-from-channel state (jget channel "id") since))
                    selected (filter (fn [message]
                                       (let [aid (author-id message)
                                             hits (moderation-hits (jget message "content"))]
                                         (or (contains? known-label-user-ids aid)
                                             (has-label-reaction? message)
                                             (seq hits))))
                                     (array-seq messages))]
                (swap! total-found + (count selected))
                (doseq [message selected]
                  (when (state-get state "runningBackfill")
                    (send-to-tracker! state message channel (or (jget channel "guild_id") "@me") "backfill")
                    (swap! total-sent inc)
                    (await (sleep-ms message-delay-ms))))
                (await (sleep-ms channel-delay-ms)))))
          (toast! (str "Backfill complete: " @total-sent "/" @total-found " messages sent") "success"))
        (catch :default err
          (log! "error" nil "Backfill failed" err)
          (toast! (str "Backfill failed: " (jget err "message")) "error"))
        (finally
          (state-set! state "runningBackfill" false))))))

(defn- bot-config-status []
  (if-let [config (bot-config)]
    (let [identity (str/trim (str (or (jget config "username") "configured bot") " " (when (present-string? (jget config "botUserId")) (str "(" (jget config "botUserId") ")"))))
          alias (when (present-string? (jget config "botServerAliasName")) (str " for " (jget config "botServerAliasName")))]
      (str "bot.json: " identity alias "; tracker/watch sends use Bot REST only"))
    "bot.json: missing or invalid token; tracker/watch sends are blocked instead of using user auth"))

(defn- append-text! [document root tag text]
  (let [node (.createElement document tag)]
    (set! (.-textContent node) text)
    (.appendChild root node)
    node))

(defn- field! [document label-text plugin key value placeholder password?]
  (let [wrap (.createElement document "label")
        label (.createElement document "span")
        input (.createElement document "input")]
    (set! (.-cssText (.-style wrap)) "display:flex;flex-direction:column;gap:6px;font-weight:600;")
    (set! (.-textContent label) label-text)
    (set! (.-type input) (if password? "password" "text"))
    (set! (.-value input) value)
    (set! (.-placeholder input) placeholder)
    (set! (.-cssText (.-style input)) "padding:8px 10px;border-radius:6px;border:1px solid var(--background-modifier-border);background:var(--background-secondary);color:var(--text-normal);")
    (.addEventListener input "change" #(save-setting! plugin key (.-value input)))
    (.append wrap label input)
    wrap))

(defn- button! [document label on-click]
  (let [btn (.createElement document "button")]
    (set! (.-textContent btn) label)
    (set! (.-cssText (.-style btn)) "padding:10px 16px;border-radius:6px;border:none;background:var(--button-background);color:var(--button-text);cursor:pointer;")
    (.addEventListener btn "click" on-click)
    btn))

(defn settings-panel [meta state]
  (when-let [document (global-get "document")]
    (let [root (.createElement document "div")
          title (.createElement document "h2")
          saved-api-key (str (setting "OpenPlannerEventIngest" "apiKey" ""))
          project (str (setting "OpenPlannerEventIngest" "project" default-project))
          key-source (cond (present-string? saved-api-key) "BetterDiscord settings"
                           (present-string? (api-key)) "$OPENPLANNER_API_KEY"
                           :else "missing")]
      (set! (.-cssText (.-style root)) "padding:16px;display:flex;flex-direction:column;gap:12px;color:var(--text-normal);")
      (set! (.-textContent title) (meta-field meta "name" plugin-name))
      (.appendChild root title)
      (.append root
               (field! document "OpenPlanner /v1/events endpoint" "OpenPlannerEventIngest" "endpoint" (endpoint) "Derived from OPENPLANNER_BASE_URL when unset" false)
               (field! document "OpenPlanner API key override" "OpenPlannerEventIngest" "apiKey" saved-api-key "Leave blank to use OPENPLANNER_API_KEY" true)
               (field! document "OpenPlanner project" "OpenPlannerEventIngest" "project" project "discord" false)
               (field! document "Moderation watch terms" "OpenPlannerEventIngest" "watchTerms" (str (setting "OpenPlannerEventIngest" "watchTerms" "")) "Comma-separated literal terms or /regex/i patterns" false)
               (field! document "Semantic query instruction" "OpenPlannerEventIngest" "semanticQueryInstruction" (str (setting "OpenPlannerEventIngest" "semanticQueryInstruction" default-semantic-query-instruction)) "Instruction prefix for moderation-watch memory searches" false)
               (append-text! document root "div" (str "API key source: " key-source))
               (append-text! document root "div" (str "Allowlisted guild IDs: " (str/join ", " guild-ids)))
               (append-text! document root "div" (str "Tracker channel: " tracker-channel-id))
               (append-text! document root "div" (str "Watch channel: " watch-channel-id))
               (append-text! document root "div" (bot-config-status))
               (append-text! document root "div" (str "Tracked users: " (.-size (state-get state "bitchCounts")) ", labeled messages: " (.-size (state-get state "labeledMessages"))))
               (button! document "Backfill Now" #(run-backfill! state))
               (button! document "Test Tracker Send" #(send-discord-message! state tracker-channel-id "[bitch-tracker-test] tracker send test")))
      root)))

(defn ^:async start-plugin! [meta state]
  (await (sleep-ms 0))
  (load-modules! state)
  (load-persisted-queue! state)
  (load-label-state! state)
  (let [dispatcher (state-get state "dispatcher")]
    (if-not (and dispatcher (jget dispatcher "subscribe"))
      (log! "error" meta "Could not find Discord Dispatcher.subscribe; lifecycle started in degraded mode")
      (do
        (state-set! state "boundMessageCreate" #(on-message-create! state %))
        (state-set! state "boundReactionAdd" #(on-reaction-add! state %))
        (state-set! state "boundReactionRemove" #(on-reaction-remove! state %))
        (jcall dispatcher "subscribe" "MESSAGE_CREATE" (state-get state "boundMessageCreate"))
        (jcall dispatcher "subscribe" "MESSAGE_REACTION_ADD" (state-get state "boundReactionAdd"))
        (jcall dispatcher "subscribe" "MESSAGE_REACTION_REMOVE" (state-get state "boundReactionRemove")))))
  (state-set! state "flushTimer" (js/setInterval #(flush! state) flush-every-ms))
  (state-set! state "retryTimer" (js/setInterval #(flush! state) retry-every-ms))
  (state-set! state "semanticScanTimer" (js/setInterval #(run-semantic-scan! state) semantic-scan-every-ms))
  (state-set! state "started" true)
  (state-set! state "startedAt" (now-iso))
  (state-set! state "stoppedAt" nil)
  (log! "info" meta "started" (str "v" (meta-field meta "version" plugin-version)))
  (toast! (str (meta-field meta "name" plugin-name) " started") "success")
  state)

(defn stop-plugin! [meta state]
  (let [dispatcher (state-get state "dispatcher")]
    (try
      (when dispatcher
        (jcall dispatcher "unsubscribe" "MESSAGE_CREATE" (state-get state "boundMessageCreate"))
        (jcall dispatcher "unsubscribe" "MESSAGE_REACTION_ADD" (state-get state "boundReactionAdd"))
        (jcall dispatcher "unsubscribe" "MESSAGE_REACTION_REMOVE" (state-get state "boundReactionRemove")))
      (catch :default err (log! "warn" meta "unsubscribe failed" err))))
  (doseq [timer-key ["flushTimer" "retryTimer" "semanticScanTimer"]]
    (when-let [timer (state-get state timer-key)]
      (js/clearInterval timer)
      (state-set! state timer-key nil)))
  (bd-data-save! "OpenPlannerEventIngest" "queue" (.slice (state-get state "queue") (- max-persisted-events)))
  (persist-label-state! state)
  (state-set! state "started" false)
  (state-set! state "stoppedAt" (now-iso))
  (state-set! state "runningBackfill" false)
  (log! "info" meta "stopped")
  (toast! (str (meta-field meta "name" plugin-name) " stopped") "info")
  state)

(defn plugin-factory [meta]
  (let [state (make-state)]
    #js {:start (fn [] (start-plugin! meta state))
         :stop (fn [] (stop-plugin! meta state))
         :getSettingsPanel (fn [] (settings-panel meta state))
         :runBackfill (fn [] (run-backfill! state))
         :flush (fn [] (flush! state))
         :state state}))

(defn main! []
  ;; BetterDiscord's current plugin loader accepts this factory shape:
  ;; module.exports = meta => ({start, stop, ...})
  (set! (.-exports js/module) plugin-factory))
