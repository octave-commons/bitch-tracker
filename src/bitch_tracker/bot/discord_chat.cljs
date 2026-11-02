(ns bitch-tracker.bot.discord-chat
  "OpenSkull bot chat integration: handles direct DMs and @OpenSkull mentions on Discord
   using Knoxx companion (gemma4:31b), executes !routine subagent routines, formats
   message permalinks and timeframe citations, and polls the GovOne governed channel."
  (:require [bitch-tracker.bot.govone :as govone]
            [bitch-tracker.bot.knoxx :as knoxx]
            [bitch-tracker.domain.lens-turn :as turn]
            [bitch-tracker.shared.util :as u]
            [clojure.string :as str]))

(defn permalink
  "Constructs a Discord message URL from snowflake IDs."
  [guild-id channel-id message-id]
  (if (and guild-id channel-id message-id)
    (str "https://discord.com/channels/" guild-id "/" channel-id "/" message-id)
    (if (and channel-id message-id)
      (str "https://discord.com/channels/@me/" channel-id "/" message-id)
      nil)))

(defn format-timeframe-citation
  "Formats a timeframe span citation linking start and end messages."
  [guild-id channel-id first-msg-id last-msg-id]
  (let [first-link (permalink guild-id channel-id first-msg-id)
        last-link (permalink guild-id channel-id last-msg-id)]
    (str "[Timeframe: " first-link " .. " last-link "]")))

(defn- clean-prompt [content bot-id]
  (let [mention-pattern (js/RegExp. (str "<@!?" bot-id ">") "g")]
    (-> (str content)
        (.replace mention-pattern "")
        str/trim)))

(defn- extract-recent-context
  "Fetches the last N messages from the channel for conversational context."
  [^js channel]
  (js/Promise.
   (fn [res _reject]
     (if-not (and channel (.-messages channel) (.-fetch (.-messages channel)))
       (res [])
       (-> (.fetch (.-messages channel) #js {:limit 10})
           (.then (fn [coll]
                    (let [arr (if coll (js/Array.from (.values coll)) #js [])
                          sorted (.reverse arr)]
                      (res
                       (mapv (fn [^js m]
                               {:id (.-id m)
                                :author (or (some-> m .-author .-username) "user")
                                :content (.-content m)
                                :timestamp (.toISOString (or (.-createdAt m) (js/Date.)))})
                             sorted)))))
           (.catch (fn [err]
                     (js/console.warn "[discord-chat] Failed to fetch channel context:" (.-message err))
                     (res []))))))))

(defn- ^:async run-chat-turn!
  "Executes a turn on Knoxx companion (gemma4:31b) with stream collection and replies to msg."
  [config ^js msg prompt & [mode]]
  (let [channel (.-channel msg)
        guild-id (some-> msg .-guild .-id)
        channel-id (.-id channel)
        channel-name (or (some-> channel .-name) "DM")
        conv-id (str "discord-chat-" channel-id)
        run-id (str (random-uuid))
        accum-text (atom "")
        completed? (atom false)]
    (try
      (when channel (.sendTyping channel))
      (catch :default _ nil))
    (let [on-envelope (fn [env]
                        (let [t (:type env)]
                          (cond
                            (= "token" t)
                            (swap! accum-text str (or (:token env) ""))

                            (or (= "run_completed" t) (= "done" t))
                            (reset! completed? true))))
          on-close (fn [] nil)]
      (try
        (let [ws (await (knoxx/open-stream! config conv-id {:on-envelope on-envelope :on-close on-close}))
              recent-items (await (extract-recent-context channel))
              turn-mode (or mode "direct")
              turn-payload (turn/turn-body
                            {:message prompt
                             :conversation-id conv-id
                             :run-id run-id
                             :mode turn-mode
                             :model (:lens-model config)
                             :channel {:id channel-id
                                       :name channel-name
                                       :guild_id guild-id}
                             :context {:items recent-items}})
              start-res (await (knoxx/start-direct-turn! config turn-payload))]
          (if-not (:ok start-res)
            (do
              (knoxx/close-stream! ws)
              (.reply msg (str "⚠️ Turn refused: " (or (get-in start-res [:body :error]) "unknown error"))))
            ;; Wait for stream completion up to 45s
            (let [start-ts (js/Date.now)]
              (js/setInterval
               (fn [interval-id]
                 (when (or @completed? (> (- (js/Date.now) start-ts) 45000))
                   (js/clearInterval interval-id)
                   (knoxx/close-stream! ws)
                   (let [final-text (str/trim @accum-text)
                         reply-text (if (str/blank? final-text)
                                      "Received empty response from Knoxx agent."
                                      final-text)]
                     (doseq [msg-chunk (u/discord-message-chunks reply-text)]
                       (.reply msg msg-chunk)))))
               500))))
        (catch :default err
          (js/console.error "[discord-chat] Turn error:" (.-message err))
          (.reply msg (str "❌ Failed to query Knoxx: " (.-message err))))))))


(defn- handle-message-create!
  [^js client config ^js msg]
  (let [author (.-author msg)]
    (when (and author (not (.-bot author)))
      (let [bot-id (some-> client .-user .-id)
            is-dm (or (nil? (.-guild msg)) (when (.-isDMBased (.-channel msg)) (.isDMBased (.-channel msg))))
            is-mentioned (and bot-id (some-> msg .-mentions (.has bot-id)))
            raw-text (or (.-content msg) "")]
        (when (or is-dm is-mentioned)
          (let [prompt (clean-prompt raw-text bot-id)
                lower-prompt (str/lower-case prompt)]
            (cond
              (or (str/starts-with? lower-prompt "!routine")
                  (str/starts-with? lower-prompt "/routine")
                  (str/includes? lower-prompt "run routine"))
              (let [clean-sub (-> prompt
                                  (str/replace #"(?i)^[!/]routine\s*" "")
                                  str/trim)
                    routine-prompt (if (str/blank? clean-sub)
                                     "Run a routine inspection of this channel: extract topics, links, attachments, and summarize recent context."
                                     clean-sub)]
                (js/console.log "[discord-chat] Launching routine turn:" routine-prompt)
                (run-chat-turn! config msg routine-prompt "routine"))

              (seq prompt)
              (do
                (js/console.log "[discord-chat] Direct turn from" (.-username author) ":" prompt)
                (run-chat-turn! config msg prompt "direct")))))))))

(defn- start-govone-channel-poller!
  [config ^js _discord-client]
  (let [seen-ids (atom #{})
        poll! (fn []
                (-> (govone/read-channel-messages! config)
                    (.then (fn [res]
                             (when (:ok res)
                               (doseq [m (:messages res)]
                                 (let [mid (:message_id m)]
                                   (when (and mid (not (contains? @seen-ids mid)))
                                     (swap! seen-ids conj mid)
                                     (js/console.log "[govone-bridge] New message from governed channel:"
                                                     (:content m) "priority:" (:priority m))))))))
                    (.catch (fn [err]
                              (js/console.warn "[govone-bridge] Poll error:" (.-message err))))))]
    ;; Poll every 8 seconds
    (js/setInterval poll! 8000)))

(defn setup-chat-listeners!
  "Wires Discord messageCreate handler and GovOne governed channel poller."
  [^js client config]
  (when client
    (.on client "messageCreate" (fn [msg] (handle-message-create! client config msg)))
    (start-govone-channel-poller! config client)
    (js/console.log "[discord-chat] OpenSkull bot chat & GovOne channel poller initialized.")))
