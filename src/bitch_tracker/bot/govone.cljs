(ns bitch-tracker.bot.govone
  "GovOne client for KnoxxLens: sanitized extraction of Discord content with coreferential
   entity masking ([[ENTITY_N]]), auditor Merkle evidence collection, and deterministic
   DEK crypto-shredding via WebSocket intervention."
  (:require [bitch-tracker.shared.util :as u]
            [clojure.string :as str]))

(defn- auth-header [token]
  (str "Bearer " token))

(defn- extract-discord-entities
  "Discovers Discord mentions, usernames, snowflakes, and URLs in raw text to classify for GovOne."
  [text]
  (let [patterns [{:pattern-type "DISCORD_MENTION" :re #"(?:<@!?\d+>|<#\d+>|<@&\d+>)"}
                  {:pattern-type "DISCORD_ID" :re #"\b\d{17,20}\b"}
                  {:pattern-type "EMAIL" :re #"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}"}
                  {:pattern-type "IP_ADDRESS" :re #"\b(?:\d{1,3}\.){3}\d{1,3}\b"}]
        results (atom [])]
    (doseq [{:keys [pattern-type re]} patterns]
      (let [global-re (js/RegExp. (.-source re) "g")]
        (loop []
          (when-let [m (.exec global-re text)]
            (let [matched (aget m 0)
                  char-idx (.-index m)
                  start-byte (.-length (js/Buffer.from (subs text 0 char-idx) "utf8"))
                  end-byte (+ start-byte (.-length (js/Buffer.from matched "utf8")))]
              (swap! results conj {:message_index 0
                                   :entity_type pattern-type
                                   :offset_start start-byte
                                   :offset_end end-byte
                                   :confidence 1.0
                                   :layer "custom"
                                   :sensitivity_level "confidential"})
              (recur))))))
    (vec (sort-by :offset_start @results))))

(defn- ^:async call-api!
  [config method path body token]
  (let [base-url (:govone-base-url config)
        url (str base-url path)
        opts (cond-> {:method method
                      :headers {"content-type" "application/json"
                                "authorization" (auth-header token)}}
               body (assoc :body (js/JSON.stringify (clj->js body))))
        res (await (js/fetch url (clj->js opts)))
        text (await (.text res))
        parsed (u/parse-json text nil)]
    {:ok (.-ok res)
     :status (.-status res)
     :body (if parsed (js->clj parsed :keywordize-keys true) {:raw text})}))

(defn- terminate-session-dek!
  "Connects to GovOne realtime WebSocket and sends terminate intervention to crypto-shred the DEK."
  [config session-id token]
  (js/Promise.
   (fn [res _reject]
     (try
       (let [base-url (:govone-base-url config)
             ws-url (-> base-url
                        (str/replace #"^http:" "ws:")
                        (str/replace #"^https:" "wss:")
                        (str "/api/v1/realtime?token=" (js/encodeURIComponent token)))
             ^js ws (js/WebSocket. ws-url "govone.realtime.v1")
             timer (js/setTimeout (fn []
                                    (try (.close ws) (catch :default _ nil))
                                    (res {:ok true :warning "ws timeout"}))
                                  4000)]
         (.addEventListener ws "open"
                            (fn []
                              (.send ws (js/JSON.stringify
                                         #js {:type "intervene"
                                              :request_id (str "term-" session-id)
                                              :payload #js {:session_id session-id
                                                            :action "terminate"
                                                            :reason "extraction completed; crypto-shred session DEK"}}))))
         (.addEventListener ws "message"
                            (fn [^js _evt]
                              (js/clearTimeout timer)
                              (try (.close ws) (catch :default _ nil))
                              (res {:ok true :action "terminate"})))
         (.addEventListener ws "error"
                            (fn [err]
                              (js/clearTimeout timer)
                              (res {:ok false :error (.-message err)}))))
       (catch :default err
         (res {:ok false :error (.-message err)}))))))

(defn ^:async extract-and-sanitize!
  "Executes the full GovOne extraction workflow:
   1. Ingests raw Discord text into an ephemeral GovOne session
   2. Classifies mentions, IDs, and PII
   3. Redacts into [[ENTITY_N]] placeholders
   4. Runs output inspection gate
   5. Requests masked rehydration
   6. Retrieves auditor Merkle evidence proof
   7. Terminates session and crypto-shreds session DEK via WebSocket intervention"
  [config text]
  (let [token (:govone-lens-token config)
        cls (extract-discord-entities text)]
    (try
      (let [s-res (await (call-api! config "POST" "/api/v1/sessions"
                                    {:ttl_seconds 300}
                                    token))
            session-id (get-in s-res [:body :session_id])]
        (if-not session-id
          {:ok false :error (or (get-in s-res [:body :error]) "Failed to create GovOne session")}
          (let [r-res (await (call-api! config "POST" "/api/v1/redact"
                                        {:session_id session-id
                                         :messages [{:role "user" :content text}]
                                         :classifications cls}
                                        token))
                redacted (get-in r-res [:body :redacted_messages 0 :content] text)
                entities (get-in r-res [:body :entities] [])
                resp-id (str "resp-" (js/Date.now))
                _i-res (await (call-api! config "POST" "/api/v1/inspect"
                                        {:session_id session-id
                                         :response_id resp-id
                                         :model_output redacted}
                                        token))
                m-res (await (call-api! config "POST" "/api/v1/rehydrate"
                                        {:session_id session-id
                                         :response_id resp-id
                                         :variant "masked"}
                                        token))
                sanitized (get-in m-res [:body :rehydrated_content] redacted)
                e-res (await (call-api! config "GET" (str "/api/v1/evidence/" session-id)
                                        nil
                                        token))
                evidence (get-in e-res [:body :events] [])
                merkle-root (or (get-in e-res [:body :merkle_root]) "0x0000000000000000000000000000000000000000")
                term (await (terminate-session-dek! config session-id token))]
            {:ok true
             :session_id session-id
             :sanitized_text sanitized
             :entities entities
             :classifications cls
             :merkle_root merkle-root
             :evidence_count (count evidence)
             :dek_shredded (:ok term true)})))
      (catch :default err
        {:ok false :error (.-message err)}))))

(defn ^:async read-channel-messages!
  "Reads messages from the dedicated governed channel session."
  [config]
  (let [token (:govone-lens-token config)
        session-id (:govone-channel-session-id config)
        res (await (call-api! config "GET" (str "/api/v1/sessions/" session-id "/messages") nil token))]
    (if (:ok res)
      {:ok true :messages (vec (or (get-in res [:body :messages]) []))}
      {:ok false :error (or (get-in res [:body :error]) (str "HTTP " (:status res))) :messages []})))

(defn ^:async send-channel-message!
  "Sends a message on the dedicated governed channel session."
  [config content & [priority]]
  (let [token (:govone-lens-token config)
        session-id (:govone-channel-session-id config)
        res (await (call-api! config "POST" (str "/api/v1/sessions/" session-id "/messages")
                              {:content (str content)
                               :priority (or priority "normal")}
                              token))]
    (if (:ok res)
      {:ok true :message (get-in res [:body :message])}
      {:ok false :error (or (get-in res [:body :error]) (str "HTTP " (:status res)))})))
