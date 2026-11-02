(ns bitch-tracker.domain.lens-context
  "KnoxxLens context selection: which Discord messages accompany a question.
  No I/O. Inputs and outputs are plain maps; Discord JS objects are converted
  at the plugin edge through the shared OpenPlanner event builder, so every
  context message carries its canonical OpenPlanner event id."
  (:require [bitch-tracker.domain.lens-topics :as topics]
            [clojure.string :as str]))

(def default-tail-limit
  "Number of most-recent channel messages included when the tail chip is on."
  45)

(def max-tail-limit
  "Upper bound for the channel-tail chip and scroll accumulator."
  1000)

(def default-budget-chars
  "Character budget for all rendered context in one turn (~4 chars per token)."
  128000)

(def chars-per-token
  "Rough characters-per-token ratio used for chip estimates."
  4)

(defn estimate-tokens
  "Returns a rough token estimate for a character count."
  [char-count]
  (js/Math.ceil (/ (max 0 char-count) chars-per-token)))

(defn clamp-tail-limit
  "Clamps a requested tail size into [0, max-tail-limit]; non-numbers fall back to the default."
  [n]
  (if (and (number? n) (not (js/isNaN n)))
    (-> n js/Math.floor (max 0) (min max-tail-limit))
    default-tail-limit))

;; ─────────────────────────────────────────────────────────────
;; Attachment & URL classification
;; ─────────────────────────────────────────────────────────────

(def ^:private url-pattern
  #"https?://[^\s<>\"')\]]+")

(defn discord-cdn-url?
  "True when `url` points at Discord's attachment/media CDN (`cdn.discordapp.com` or `media.discordapp.net`)."
  [url]
  (boolean (re-find #"(?i)^https?://(?:cdn\.discordapp\.com|media\.discordapp\.net)/" (str url))))

(defn- url-path
  "Returns the path portion of a URL without query parameters or fragments."
  [url]
  (-> (str url)
      (str/split #"[?#]" 2)
      first))

(defn url-filename
  "Extracts a clean filename from a URL path, falling back to `fallback`."
  ([url] (url-filename url "attachment"))
  ([url fallback]
   (let [path (url-path url)
         seg (last (str/split path #"/"))]
     (if (and (seq seg) (not (str/starts-with? seg "http")))
       (try (js/decodeURIComponent seg) (catch :default _ seg))
       (or (not-empty (str fallback)) "attachment")))))

(defn infer-image-mime
  "Infers an image MIME type from explicit content-type, filename, or URL path.
  Returns nil when the attachment does not look like a supported raster image."
  [url content-type filename]
  (let [ct (str/lower-case (str (or content-type "")))
        target (str/lower-case (str (or filename "") " " (url-path url)))]
    (cond
      (str/starts-with? ct "image/png") "image/png"
      (or (str/starts-with? ct "image/jpeg") (str/starts-with? ct "image/jpg")) "image/jpeg"
      (str/starts-with? ct "image/webp") "image/webp"
      (str/starts-with? ct "image/gif") "image/gif"
      (re-find #"\.png(?:\b|$)" target) "image/png"
      (re-find #"\.(?:jpe?g)(?:\b|$)" target) "image/jpeg"
      (re-find #"\.webp(?:\b|$)" target) "image/webp"
      (re-find #"\.gif(?:\b|$)" target) "image/gif"
      :else nil)))

(defn- normalize-attachment-entry
  "Normalizes a raw attachment map or URL string into {:url :filename :mime-type :image?}."
  [a]
  (let [m (if (map? a) a {:url (str a)})
        url (str (or (:url m) (:proxy_url m) (:proxyUrl m) ""))]
    (when-not (str/blank? url)
      (let [fname (url-filename url (or (:filename m) (:name m)))
            mime (infer-image-mime url (or (:content_type m) (:contentType m) (:mimeType m)) fname)]
        {:url url
         :filename fname
         :mime-type (or mime (some-> (or (:content_type m) (:contentType m)) str not-empty))
         :image? (boolean mime)}))))

(defn- clean-message-text
  "Strips fallback attachment URLs and raw Discord CDN attachment links from message `:text`
  so image-only posts and pasted screenshots are not mistaken for user prose or URL discussions."
  [raw-text attachment-urls]
  (let [s (str (or raw-text ""))
        att-set (set (map str attachment-urls))
        lines (->> (str/split-lines s)
                   (map (fn [line]
                          (let [trimmed (str/trim line)]
                            (if (or (contains? att-set trimmed)
                                    (and (discord-cdn-url? trimmed)
                                         (not (str/includes? trimmed " "))))
                              ""
                              (str/replace line url-pattern
                                           (fn [u]
                                             (if (or (contains? att-set u) (discord-cdn-url? u))
                                               ""
                                               u)))))))
                   (map str/trim)
                   (remove str/blank?))]
    (str/join "\n" lines)))

;; ─────────────────────────────────────────────────────────────
;; Context messages
;; ─────────────────────────────────────────────────────────────

(defn event->context-message
  "Projects an OpenPlanner discord.message event map (keyword keys) onto a context message.
  Separates image/file attachments and Discord CDN links from conversational `:text`."
  [event]
  (let [extra (:extra event)
        event-meta (:meta event)
        raw-text (str (or (:text event) ""))
        raw-attachments (vec (or (:attachments extra) []))
        inline-cdn-urls (->> (re-seq url-pattern raw-text)
                             (filter discord-cdn-url?)
                             distinct)
        att-entries (->> (concat raw-attachments
                                 (map (fn [u] {:url u}) inline-cdn-urls))
                         (keep normalize-attachment-entry)
                         (reduce (fn [acc entry]
                                   (if (some #(= (:url entry) (:url %)) acc)
                                     acc
                                     (conj acc entry)))
                                 []))
        att-urls (mapv :url att-entries)
        images (->> att-entries
                    (filter :image?)
                    (mapv (fn [{:keys [url filename mime-type]}]
                            {:type "image"
                             :url url
                             :mimeType (or mime-type "image/png")
                             :filename (or filename "image.png")})))]
    {:event-id (str (:id event))
     :message-id (str (:message_id extra))
     :channel-id (str (:channel_id extra))
     :guild-id (str (:guild_id extra))
     :ts (str (:ts event))
     :author (str (or (:author_global_name event-meta) (:author event-meta) (:author_id event-meta) "unknown"))
     :author-id (str (:author_id event-meta))
     :text (clean-message-text raw-text att-urls)
     :attachments att-urls
     :attachment-meta att-entries
     :images images}))

(defn- message-sort-key
  "Chronological sort key for context messages: ISO timestamp first, then Discord snowflake id."
  [m]
  (let [mid (str (:message-id m))]
    [(str (:ts m)) (count mid) mid (str (:event-id m))]))

(defn merge-messages
  "Merges existing and incoming context messages for a channel without unloading
  messages when Discord's sliding MessageStore window shifts during scroll.
  Deduplicates by :event-id (incoming overwrites existing), sorts oldest-first,
  and retains up to max-tail-limit messages."
  [existing incoming]
  (->> (concat (or existing []) (or incoming []))
       (remove #(str/blank? (str (:event-id %))))
       (reduce (fn [acc m] (assoc acc (:event-id m) m)) {})
       vals
       (sort-by message-sort-key)
       (take-last max-tail-limit)
       vec))

(defn- minute-stamp
  "Shortens an ISO timestamp to minute precision for compact transcripts."
  [ts]
  (let [s (str ts)]
    (if (>= (count s) 16)
      (str (subs s 0 10) " " (subs s 11 16) "Z")
      s)))

(defn- defang-tags
  "Prevents message text from closing or opening a discord_context block."
  [text]
  (str/replace (str text) #"(?i)<(/?)discord_context" "‹$1discord_context"))

(defn- render-attachment-label
  "Formats an attachment entry as a concise descriptor without leaking raw Discord CDN URLs."
  [entry]
  (let [{:keys [filename image?]} (if (map? entry) entry (normalize-attachment-entry entry))
        clean-name (defang-tags (or (not-empty filename) "attachment"))]
    (if image?
      (str "[attached image: " clean-name "]")
      (str "[attached file: " clean-name "]"))))

(defn render-message
  "Renders one context message as a single transcript line.
  Replaces raw Discord CDN attachment URLs with clean `[attached image: ...]` markers."
  [{:keys [ts author text attachments attachment-meta]}]
  (let [cleaned-text (clean-message-text text attachments)
        body (str/trim (defang-tags (str/replace cleaned-text #"\s*\n\s*" " ⏎ ")))
        entries (if (seq attachment-meta)
                  attachment-meta
                  (keep normalize-attachment-entry attachments))
        files (when (seq entries)
                (str (when (seq body) " ")
                     (str/join " " (map render-attachment-label entries))))]
    (str "[" (minute-stamp ts) "] " (defang-tags author) ": " body files)))

;; ─────────────────────────────────────────────────────────────
;; Smart Context: Channel Map & Query-Matched History
;; ─────────────────────────────────────────────────────────────

(defn extract-query-terms
  "Extracts lowercase noun and proper-noun search terms from text, filtering out
   verbs, prepositions, adjectives, URLs, and conversational filler."
  [text]
  (->> (topics/extract-fallback-noun-terms text)
       (map #(str/lower-case (str (:term %))))
       (remove str/blank?)
       distinct
       (take 14)
       vec))

(defn- normalize-top-terms
  [override-topics cleaned-messages]
  (if (seq override-topics)
    (->> override-topics
         (keep (fn [item]
                 (if (map? item)
                   (when (seq (str/trim (str (:term item)))) item)
                   (let [s (str/trim (str item))]
                     (when (seq s) {:term s :kind "noun"})))))
         (take 12)
         vec)
    (topics/extract-fallback-topics cleaned-messages)))

(defn extract-channel-summary
  "Summarizes a vector of cached context messages into structured channel intelligence:
  {:message-count :oldest-ts :newest-ts :participants :urls :image-count :mentioned-channels :top-terms}.
  Accepts optional `override-topics` from neural NER + POS tagging or `lens_refine_topics`."
  ([messages]
   (extract-channel-summary messages nil))
  ([messages override-topics]
   (let [n (count messages)]
     (when (pos? n)
       (let [oldest (minute-stamp (:ts (first messages)))
             newest (minute-stamp (:ts (last messages)))
             participants (->> messages
                               (map #(or (not-empty (:author %)) "unknown"))
                               frequencies
                               (sort-by val >)
                               (take 12)
                               (mapv (fn [[author cnt]] {:author author :count cnt})))
             att-url-set (set (mapcat :attachments messages))
             urls (->> messages
                       (mapcat #(re-seq url-pattern (str (:text %))))
                       (remove #(or (discord-cdn-url? %)
                                    (contains? att-url-set %)))
                       distinct
                       (take 12)
                       vec)
             image-count (reduce + 0 (map (fn [{:keys [images attachments]}]
                                            (if (seq images)
                                              (count images)
                                              (count (filter #(infer-image-mime % nil nil) attachments))))
                                          messages))
             channels (->> messages
                           (mapcat #(map second (re-seq #"<#(\d+)>" (str (:text %)))))
                           distinct
                           (take 10)
                           vec)
             cleaned-messages (mapv (fn [m]
                                      (assoc m :text (clean-message-text (:text m) (:attachments m))))
                                    messages)
             top-terms (normalize-top-terms override-topics cleaned-messages)]
         {:message-count n
          :oldest-ts oldest
          :newest-ts newest
          :participants participants
          :urls urls
          :image-count image-count
          :mentioned-channels channels
          :top-terms top-terms})))))

(defn- topic-label
  [item]
  (defang-tags (if (map? item) (str (:term item)) (str item))))

(defn render-channel-map-lines
  "Renders the structured channel intelligence summary as compact lines."
  [channel summary]
  (let [{:keys [message-count oldest-ts newest-ts participants urls image-count top-terms]} summary
        part-str (str/join ", " (map (fn [{:keys [author] cnt :count}] (str (defang-tags author) " (" cnt ")")) participants))
        term-labels (->> top-terms (map topic-label) (remove str/blank?) vec)]
    (cond-> [(str "Channel: #" (defang-tags (:name channel)) " (id=" (:id channel)
                  (when (seq (:guild-id channel)) (str ", guild_id=" (:guild-id channel)))
                  (when (seq (:guild-name channel)) (str ", guild=" (defang-tags (:guild-name channel)))) ")")
             (str "Cached scroll window: " message-count " messages (" oldest-ts " -> " newest-ts ")")
             (str "Active participants: " part-str)]
      (seq term-labels) (conj (str "Channel noun & entity topics: " (str/join ", " term-labels)))
      (pos? (or image-count 0)) (conj (str "Image attachments in buffer: " image-count " (recent/pinned images attached as multimodal vision input)"))
      (seq urls) (conj (str "Shared external URLs in buffer: " (str/join " | " (map defang-tags urls)))))))

(defn match-older-messages
  "Finds older messages (outside the recent tail) that match terms in query,
  including +/- 1 surrounding message for conversational context."
  [older-messages query max-matches]
  (let [terms (extract-query-terms query)
        v (vec older-messages)
        n (count v)]
    (if (or (empty? terms) (zero? n))
      []
      (let [scored (->> (range n)
                        (keep (fn [idx]
                                (let [m (nth v idx)
                                      hay (str/lower-case (str (:author m) " " (:text m)))
                                      hits (count (filter #(str/includes? hay %) terms))]
                                  (when (pos? hits)
                                    {:idx idx :hits hits}))))
                        (sort-by (fn [{:keys [idx hits]}] [(- hits) (- idx)]))
                        (take (max 1 (quot (or max-matches 16) 2))))
            expanded-idxs (->> scored
                               (mapcat (fn [{:keys [idx]}]
                                         [(max 0 (dec idx)) idx (min (dec n) (inc idx))]))
                               distinct
                               sort
                               (take (or max-matches 16)))]
        (mapv #(nth v %) expanded-idxs)))))

(defn contextual-suggestions
  "Generates up to 4 dynamic, context-relevant starter questions or draft prompts
  (OpenAI / Gemini / Claude style) from the active channel, its summary, and pinned items."
  [channel {:keys [participants top-terms urls]} pinned]
  (let [ch-name (if (seq (:name channel)) (str "#" (:name channel)) "this channel")
        p1 (some-> participants first :author)
        p2 (some-> participants second :author)
        term-str (fn [x] (if (map? x) (str (:term x)) (str x)))
        t1 (some-> top-terms first term-str not-empty)
        t2 (some-> top-terms second term-str not-empty)
        candidates (cond-> []
                     (seq pinned)
                     (conj "Synthesize the pinned context and identify the core decision or next step")

                     (and p1 t1)
                     (conj (str "What is " p1 "'s main point about \"" t1 "\""
                                (when t2 (str " and \"" t2 "\"")) "?"))

                     (and p1 p2)
                     (conj (str "How do " p1 " and " p2 "'s perspectives compare in " ch-name "?"))

                     t1
                     (conj (str "Query graph memory for how \"" t1 "\" connects across channels & sessions"))

                     (seq urls)
                     (conj (str "Read the shared links in " ch-name " and summarize how they connect to this discussion"))

                     :always
                     (conj (str "Draft a message for " ch-name
                                (if t1 (str " responding to the \"" t1 "\" thread") " summarizing our next step"))
                           (str "What open questions or unresolved decisions are in " ch-name "?")))]
    (vec (take 4 (distinct candidates)))))

;; ─────────────────────────────────────────────────────────────
;; Selection (per channel)
;; ─────────────────────────────────────────────────────────────

(defn empty-selection
  "Returns the default selection for a channel: tail chip on, nothing pinned."
  [channel]
  {:channel channel
   :tail-enabled? true
   :tail-limit default-tail-limit
   :pinned []
   :auto-pinned-ids #{}})

(defn pinned?
  "True when the event id is already pinned in the selection."
  [selection event-id]
  (boolean (some #(= event-id (:event-id %)) (:pinned selection))))

(defn pin-message
  "Adds a context message to the pinned list, ignoring duplicates by event id."
  [selection message]
  (if (or (str/blank? (:event-id message)) (pinned? selection (:event-id message)))
    selection
    (update selection :pinned (fnil conj []) message)))

(defn parse-grabbed-context
  "Extracts cross-channel context items from `<grabbed_context>{...}</grabbed_context>`
   envelopes in `lens_grab_context` tool receipts or assistant text."
  [raw-text]
  (let [matches (re-seq #"(?i)<\s*grabbed_context\s*>([\s\S]*?)<\s*/\s*grabbed_context\s*>"
                        (str (or raw-text "")))]
    (when (seq matches)
      (->> matches
           (mapcat (fn [[_ payload]]
                     (try
                       (let [parsed (js->clj (.parse js/JSON (str/trim payload)) :keywordize-keys true)]
                         (or (:items parsed) []))
                       (catch :default _ []))))
           (keep (fn [item]
                   (let [eid (some-> (or (:eventId item) (:event-id item) (:id item)) str str/trim not-empty)
                         txt (some-> (:text item) str str/trim not-empty)]
                     (when (and eid txt)
                       {:event-id eid
                        :channel-id (str (or (:channelId item) (:channel-id item) ""))
                        :channel-name (some-> (or (:channelName item) (:channel-name item)) str str/trim not-empty)
                        :guild-id (str (or (:guildId item) (:guild-id item) ""))
                        :guild-name (some-> (or (:guildName item) (:guild-name item)) str str/trim not-empty)
                        :scope (str (or (:scope item) "cross-channel"))
                        :author (str (or (:author item) "cross-channel"))
                        :ts (str (or (:ts item) ""))
                        :score (:score item)
                        :window-count (:windowCount item)
                        :preview (str (or (:preview item) txt))
                        :text txt}))))
           (reduce (fn [acc entry]
                     (if (some #(= (:event-id entry) (:event-id %)) acc)
                       acc
                       (conj acc entry)))
                   [])
           not-empty))))

(defn grabbed-item->context-message
  "Converts a parsed `<grabbed_context>` item into a pinnable cross-channel context message."
  [item]
  {:event-id (str (:event-id item))
   :message-id (str (:event-id item))
   :channel-id (str (:channel-id item))
   :channel-name (:channel-name item)
   :guild-id (str (:guild-id item))
   :guild-name (:guild-name item)
   :scope (or (:scope item) "cross-channel")
   :grabbed? true
   :preview (or (:preview item) (:text item))
   :ts (or (not-empty (str (:ts item))) (.toISOString (js/Date.)))
   :author (or (not-empty (str (:author item))) "cross-channel")
   :author-id (str (:event-id item))
   :text (str (:text item))
   :attachments []})

(defn pin-grabbed-items
  "Auto-pins newly grabbed cross-channel items into `selection` (up to 6 per turn),
   recording their ids in `:auto-pinned-ids` so user-removed (`×`) chips are not re-added."
  [selection grabbed-items]
  (let [open-ch-id (str (get-in selection [:channel :id] ""))]
    (reduce (fn [sel item]
              (let [eid (str (:event-id item))
                    seen? (contains? (set (:auto-pinned-ids sel)) eid)
                    same-open-ch? (and (seq open-ch-id) (= open-ch-id (str (:channel-id item))))]
                (if (or (str/blank? eid) seen? same-open-ch?)
                  sel
                  (-> sel
                      (pin-message (grabbed-item->context-message item))
                      (update :auto-pinned-ids (fnil conj #{}) eid)))))
            selection
            (take 6 grabbed-items))))

(defn graph-node->context-message
  "Converts a knowledge-graph node map and optional incident edges into a pinnable context item."
  ([node]
   (graph-node->context-message node (:edges node) nil))
  ([node edges-or-channel]
   (if (sequential? edges-or-channel)
     (graph-node->context-message node edges-or-channel nil)
     (graph-node->context-message node (:edges node) edges-or-channel)))
  ([{:keys [id kind nodeType label text]} incident-edges channel]
   (let [edge-summary (when (seq incident-edges)
                        (str " [edges: "
                             (str/join ", "
                                       (map (fn [{:keys [edgeKind source target dir peer] e-kind :kind}]
                                              (let [k (or e-kind edgeKind "edge")]
                                                (if (and dir peer)
                                                  (str k " " dir " " peer)
                                                  (str k "(" source "->" target ")"))))
                                            (take 6 incident-edges)))
                             "]"))]
     {:event-id (str id)
      :message-id (str id)
      :channel-id (str (:id channel))
      :guild-id (str (:guild-id channel))
      :ts (.toISOString (js/Date.))
      :author (str "graph:" (or kind nodeType "node"))
      :author-id (str id)
      :text (str (or label id) (when (and (seq (str text)) (not= (str text) (str label))) (str " — " text)) edge-summary)
      :attachments []})))

(defn unpin-message
  "Removes a pinned message by event id."
  [selection event-id]
  (update selection :pinned #(vec (remove (fn [m] (= event-id (:event-id m))) %))))

(defn set-tail-limit
  "Sets the tail size; zero turns the tail chip off."
  [selection n]
  (let [limit (clamp-tail-limit n)]
    (assoc selection :tail-limit (if (pos? limit) limit default-tail-limit) :tail-enabled? (pos? limit))))

(defn toggle-tail
  "Flips the channel-tail chip."
  [selection]
  (update selection :tail-enabled? not))

(defn clear-pins
  "Removes all pinned messages."
  [selection]
  (assoc selection :pinned []))

(defn parse-directives
  "Extracts composer directives. `@last:N` sets the tail size (N=0 disables it).
  Returns {:text cleaned-text :tail-limit n-or-nil}."
  [text]
  (let [s (str text)
        match (re-find #"(?i)(?:^|\s)@last:(\d+)\b" s)]
    {:text (-> s (str/replace #"(?i)(?:^|\s)@last:\d+\b" " ") str/trim)
     :tail-limit (when match (js/parseInt (second match) 10))}))

;; ─────────────────────────────────────────────────────────────
;; Resolution into rendered context items
;; ─────────────────────────────────────────────────────────────

(defn- block
  "Wraps rendered lines in a tagged block the system prompt marks as untrusted data."
  [attrs lines]
  (str "<discord_context "
       (str/join " " (map (fn [[k v]] (str (name k) "=\"" (str/replace (str v) "\"" "'") "\"")) attrs))
       ">\n"
       (str/join "\n" lines)
       "\n</discord_context>"))

(defn- take-tail-within
  "Takes the newest messages whose rendered lines fit in budget, returned oldest-first."
  [messages budget]
  (loop [remaining (reverse messages) used 0 kept '()]
    (if-let [m (first remaining)]
      (let [cost (inc (count (render-message m)))]
        (if (> (+ used cost) budget)
          {:messages (vec kept) :char-count used :dropped (count remaining)}
          (recur (rest remaining) (+ used cost) (conj kept m))))
      {:messages (vec kept) :char-count used :dropped 0})))

(defn- extract-message-images
  "Extracts multimodal image descriptors `{:type \"image\" :url ... :mimeType ... :filename ...}`
  from a context message (supporting both `:images` and raw `:attachments` URLs)."
  [{:keys [images attachments]}]
  (if (seq images)
    (vec images)
    (->> attachments
         (keep normalize-attachment-entry)
         (filter :image?)
         (mapv (fn [{:keys [url filename mime-type]}]
                 {:type "image"
                  :url url
                  :mimeType (or mime-type "image/png")
                  :filename (or filename "image.png")})))))

(defn- collect-recent-images
  "Collects up to `max-images` deduplicated image descriptors from `messages`,
  prioritizing the newest messages in the sequence."
  [messages max-images]
  (->> (reverse messages)
       (mapcat extract-message-images)
       (reduce (fn [acc img]
                 (if (or (>= (count acc) max-images)
                         (some #(= (:url img) (:url %)) acc))
                   acc
                   (conj acc img)))
               [])
       reverse
       vec))

(defn resolve-context
  "Turns a selection plus the channel's loaded messages into rendered context items.
  Pinned messages are explicit and always included; the channel tail fills the
  remaining budget newest-first and skips messages that are already pinned.
  When `opts` ({:query :include-map? :already-sent-ids}) is supplied, also builds a
  Channel Intelligence Map across the full scroll buffer, pulls query-matched older
  messages from outside the tail window, and deduplicates already-sent tail messages
  on follow-up turns.
  Returns {:items [{:kind :label :text :event-ids :images}] :images [...] :chars n :dropped n :over-budget? bool :summary map}."
  ([selection tail-messages]
   (resolve-context selection tail-messages default-budget-chars nil))
  ([selection tail-messages budget-chars]
   (resolve-context selection tail-messages budget-chars nil))
  ([{:keys [channel pinned tail-enabled? tail-limit]} tail-messages budget-chars
    {:keys [query include-map? already-sent-ids override-topics]}]
   (let [channel-attrs [[:guild (:guild-name channel)] [:channel (str "#" (:name channel))] [:channel_id (:id channel)]]
         pinned-lines (mapv render-message pinned)
         pinned-chars (reduce + 0 (map (comp inc count) pinned-lines))
         pinned-images (collect-recent-images pinned 4)
         pinned-item (when (seq pinned)
                       (cond-> {:kind :pinned
                                :label (str (count pinned) " pinned message" (when (not= 1 (count pinned)) "s"))
                                :text (block (conj channel-attrs [:kind "pinned"]) pinned-lines)
                                :event-ids (mapv :event-id pinned)}
                         (seq pinned-images) (assoc :images pinned-images)))
         pinned-ids (set (map :event-id pinned))
         summary (when include-map? (extract-channel-summary tail-messages override-topics))
         map-lines (when summary (render-channel-map-lines channel summary))
         map-chars (if (seq map-lines) (reduce + 0 (map (comp inc count) map-lines)) 0)
         map-item (when (seq map-lines)
                    {:kind :channel-map
                     :label (str "🗺️ #" (:name channel) " map (" (:message-count summary) " cached)")
                     :text (block (conj channel-attrs [:kind "channel-map"] [:cached (:message-count summary)])
                                  map-lines)
                     :event-ids []})
         unpinned (remove #(contains? pinned-ids (:event-id %)) tail-messages)
         raw-tail (when tail-enabled? (vec (take-last tail-limit unpinned)))
         sent-set (set (or already-sent-ids #{}))
         tail-candidates (if (and (seq raw-tail) (seq sent-set))
                           (let [unsent (filterv #(not (contains? sent-set (:event-id %))) raw-tail)]
                             (if (seq unsent)
                               unsent
                               (vec (take-last (min 6 (count raw-tail)) raw-tail))))
                           raw-tail)
         tail-ids (set (map :event-id tail-candidates))
         older-pool (when (and include-map? (not (str/blank? (str query))))
                      (filterv #(not (contains? tail-ids (:event-id %))) unpinned))
         matched-older (when (seq older-pool)
                         (match-older-messages older-pool query 14))
         matched-lines (mapv render-message matched-older)
         matched-chars (reduce + 0 (map (comp inc count) matched-lines))
         matched-item (when (seq matched-older)
                        {:kind :matched-history
                         :label (str "🔎 " (count matched-older) " matched older msg" (when (not= 1 (count matched-older)) "s"))
                         :text (block (conj channel-attrs [:kind "matched-history"] [:matches (count matched-older)])
                                      matched-lines)
                         :event-ids (mapv :event-id matched-older)})
         remaining-budget (max 0 (- budget-chars pinned-chars map-chars matched-chars))
         {:keys [messages char-count dropped]} (take-tail-within tail-candidates remaining-budget)
         delta-mode? (and (seq sent-set) (< (count messages) (count raw-tail)))
         tail-images (collect-recent-images messages (max 0 (- 4 (count pinned-images))))
         tail-item (when (seq messages)
                     (cond-> {:kind (if delta-mode? :channel-delta :channel-tail)
                              :label (str "#" (:name channel)
                                          (if delta-mode?
                                            (str " · +" (count messages) " new/anchor")
                                            (str " · last " (count messages))))
                              :text (block (conj channel-attrs
                                                 [:kind (if delta-mode? "channel-delta" "channel-tail")]
                                                 [:messages (count messages)])
                                           (mapv render-message messages))
                              :event-ids (mapv :event-id messages)}
                       (seq tail-images) (assoc :images tail-images)))
         all-items (vec (remove nil? [pinned-item map-item matched-item tail-item]))
         all-images (vec (take 4 (distinct (mapcat :images all-items))))]
     {:items all-items
      :images all-images
      :chars (+ pinned-chars map-chars matched-chars char-count)
      :dropped dropped
      :over-budget? (> pinned-chars budget-chars)
      :summary summary})))
