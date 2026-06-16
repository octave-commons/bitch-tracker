(ns bitch-tracker.law.schemas
  "Malli schemas for every boundary-crossing shape in bitch-tracker.

  These schemas are pure data: they describe the grammar of values that cross
  between the Discord plugin, the bot, and OpenPlanner. They contain no I/O
  and no domain policy.")

(def OpenPlannerEventId
  "OpenPlanner event identifier: discord:<guild>:<channel>:<message>."
  [:re {:error/message "should match discord:<guild>:<channel>:<message>"}
   #"^discord:[^:]+:[^:]+:[^:]+$"])

(def Attachment
  "Discord attachment translated to a Clojure map."
  [:map
   [:id :string]
   [:filename {:optional true} [:maybe :string]]
   [:content_type {:optional true} [:maybe :string]]
   [:size {:optional true} [:maybe :int]]
   [:url {:optional true} [:maybe :string]]
   [:proxy_url {:optional true} [:maybe :string]]
   [:width {:optional true} [:maybe :int]]
   [:height {:optional true} [:maybe :int]]])

(def EventMeta
  "OpenPlanner event metadata."
  [:map
   [:author :string]
   [:author_id :string]
   [:author_username {:optional true} [:maybe :string]]
   [:author_global_name {:optional true} [:maybe :string]]
   [:bot :boolean]
   [:tags [:vector :string]]])

(def OpenPlannerEvent
  "Closed OpenPlanner event envelope, schema-version 1."
  [:map
   {:closed true}
   [:schema [:= "openplanner.event.v1"]]
   [:schema_version [:= 1]]
   [:id OpenPlannerEventId]
   [:ts :string]
   [:source :string]
   [:kind :string]
   [:source_ref [:map [:project :string] [:session :string] [:message :string]]]
   [:text :string]
   [:meta EventMeta]
   [:extra [:map]]])

(def LabelPayload
  "Closed payload for a label added/removed socket message."
  [:map
   {:closed true}
   [:user-id :string]
   [:message-id :string]
   [:reactor-id :string]
   [:message [:maybe :any]]
   [:channel [:maybe :any]]
   [:guild-id :string]])

(def BotConfig
  "Closed bot configuration map. All keys are required and typed."
  [:map
   {:closed true}
   [:token :string]
   [:app-id :string]
   [:bot-user-id :string]
   [:bot-username :string]
   [:plugin-status-channel-id :string]
   [:slapper-of-bitches-role-id :string]
   [:socket-port :int]
   [:public-base-url :string]
   [:openplanner-base-url :string]
   [:openplanner-api-key :string]
   [:openplanner-project :string]
   [:tracker-channel-id :string]
   [:watch-channel-id :string]
   [:label-threshold :int]
   [:flush-every-ms :int]
   [:semantic-scan-every-ms :int]
   [:max-batch-size :int]
   [:max-persisted-events :int]
   [:backfill-days :int]])

(def Regex
  "Portable regex object predicate (java.util.regex.Pattern or js/RegExp)."
  [:fn
   {:error/message "should be a regular expression object"}
   #?(:clj #(instance? java.util.regex.Pattern %)
      :cljs #(instance? js/RegExp %))])

(def WatchEntry
  "Closed moderation watch entry with a compiled regular expression."
  [:map
   {:closed true}
   [:label :string]
   [:pattern Regex]])

(def SocketMessage
  "Closed, discriminated socket message. Dispatch is on the :op keyword."
  [:multi
   {:dispatch :op}
   [:backfill [:map {:closed true} [:op [:= :backfill]] [:ts :string]]]
   [:config-request [:map {:closed true} [:op [:= :config-request]]]]
   [:event [:map {:closed true} [:op [:= :event]] [:event OpenPlannerEvent]]]
   [:label-added [:map {:closed true} [:op [:= :label-added]] [:payload LabelPayload]]]
   [:label-removed [:map {:closed true} [:op [:= :label-removed]] [:payload LabelPayload]]]
   [:plugin-identify [:map {:closed true} [:op [:= :plugin-identify]] [:identity [:map [:user-id :string] [:username :string] [:hostname [:maybe :string]]]]]]
   [:status [:map {:closed true} [:op [:= :status]] [:status :string] [:bot-user-id [:maybe :string]] [:ts [:maybe :string]]]]
   [:watch-alert [:map {:closed true} [:op [:= :watch-alert]] [:user-id :string] [:message-id :string] [:count :int] [:ts [:maybe :string]]]]])
