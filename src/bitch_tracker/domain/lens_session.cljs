(ns bitch-tracker.domain.lens-session
  "Per-channel KnoxxLens conversation state, tool-receipt folding, and past-session hydration.
  No I/O. A conversation is {:conversation-id :session-id :turns [...] :running-request}."
  (:require [bitch-tracker.domain.lens-context :as ctx]
            [bitch-tracker.domain.lens-topics :as topics]
            [clojure.string :as str]))

(defn new-conversation
  "Returns an empty conversation bound to a fresh Knoxx conversation id."
  [conversation-id]
  {:conversation-id conversation-id
   :session-id nil
   :turns []
   :sent-event-ids #{}
   :running-request nil})

(defn running?
  "True while a turn is awaiting completion."
  [conversation]
  (some? (:running-request conversation)))

(defn begin-turn
  "Appends the user's question and a pending assistant turn tagged with request-id."
  ([conversation request-id question context-labels]
   (begin-turn conversation request-id question context-labels nil))
  ([conversation request-id question context-labels event-ids]
   (-> conversation
       (update :turns conj
               {:role :user :text question :context context-labels}
               {:role :assistant
                :request-id request-id
                :text ""
                :status :pending
                :events []
                :tool-receipts []})
       (update :sent-event-ids #(into (set (or % #{})) (remove str/blank? (map str event-ids))))
       (assoc :running-request request-id))))

(defn- update-turn [conversation request-id f]
  (update conversation :turns
          (fn [turns] (mapv #(if (= request-id (:request-id %)) (f %) %) turns))))

(defn- settle [conversation request-id]
  (cond-> conversation
    (= request-id (:running-request conversation)) (assoc :running-request nil)))

(defn- find-receipt-index [receipts tool-call-id tool-name]
  (let [n (count receipts)]
    (or (when (seq (str tool-call-id))
          (first (keep-indexed (fn [idx r] (when (= (str tool-call-id) (str (:id r))) idx)) receipts)))
        (first (keep-indexed (fn [idx _]
                               (let [rev-idx (- (dec n) idx)
                                     candidate (nth receipts rev-idx)]
                                 (when (and (= :running (:status candidate))
                                            (= (str tool-name) (str (:tool candidate))))
                                   rev-idx)))
                             receipts)))))

(defn fold-tool-event
  "Folds a single tool event ({:type :tool :toolCallId :preview :status :isError})
  into a vector of tool receipts."
  [receipts {:keys [tool toolCallId preview isError] event-type :type}]
  (let [receipts (vec (or receipts []))
        tool-name (str (or tool "tool"))
        call-id (some-> toolCallId str not-empty)
        idx (find-receipt-index receipts call-id tool-name)]
    (case (str event-type)
      "tool_start"
      (let [id (or call-id (str tool-name "#" (inc (count receipts))))
            entry {:id id
                   :tool tool-name
                   :status :running
                   :input-preview (some-> preview str not-empty)
                   :updates []
                   :output-preview nil
                   :is-error false}]
        (if (number? idx)
          (update receipts idx #(merge % (select-keys entry [:status :input-preview])))
          (conj receipts entry)))

      "tool_update"
      (if (number? idx)
        (update receipts idx
                (fn [r]
                  (cond-> r
                    (seq (str preview))
                    (update :updates #(vec (take-last 8 (conj (vec %) (str preview))))))))
        (conj receipts {:id (or call-id (str tool-name "#" (inc (count receipts))))
                        :tool tool-name
                        :status :running
                        :input-preview nil
                        :updates (if (seq (str preview)) [(str preview)] [])
                        :output-preview nil
                        :is-error false}))

      "tool_end"
      (let [err? (boolean isError)
            status (if err? :error :done)]
        (if (number? idx)
          (update receipts idx
                  (fn [r]
                    (cond-> (assoc r :status status :is-error err?)
                      (seq (str preview)) (assoc :output-preview (str preview)))))
          (conj receipts {:id (or call-id (str tool-name "#" (inc (count receipts))))
                          :tool tool-name
                          :status status
                          :input-preview nil
                          :updates []
                          :output-preview (some-> preview str not-empty)
                          :is-error err?})))

      receipts)))

(defn- finalize-receipts [receipts terminal-status]
  (mapv (fn [r]
          (if (= :running (:status r))
            (assoc r
                   :status terminal-status
                   :is-error (or (:is-error r) (= :error terminal-status)))
            r))
        (vec (or receipts []))))

(defn- ascii-alnum-lower
  [ch]
  (let [c (.charCodeAt ch 0)]
    (cond
      (and (>= c 48) (<= c 57)) ch
      (and (>= c 65) (<= c 90)) (js/String.fromCharCode (+ c 32))
      (and (>= c 97) (<= c 122)) ch
      :else nil)))

(defn- index-alnum
  [s]
  (let [n (count s)]
    (loop [i 0
           idxs (transient [])
           chs (transient [])]
      (if (>= i n)
        {:idxs (persistent! idxs)
         :norm (apply str (persistent! chs))}
        (let [ch (.charAt s i)]
          (if-let [lc (ascii-alnum-lower ch)]
            (recur (inc i) (conj! idxs i) (conj! chs lc))
            (recur (inc i) idxs chs)))))))

(defn- rewind-markdown-prefix
  "Walks backwards from the first alphanumeric char of the second pass over
   leading Markdown heading/emphasis/emoji characters (`### ⚡ `) without
   crossing into the end of the first pass (`</suggestions>` or sentence punctuation)."
  [s alnum-start]
  (loop [i alnum-start]
    (if (<= i 0)
      0
      (let [prev (.charAt s (dec i))]
        (if (or (= "\n" prev)
                (= "\r" prev)
                (= ">" prev)
                (some? (ascii-alnum-lower prev))
                (contains? #{"." "!" "?" ")" "]" "\"" "'" "`"} prev))
          i
          (recur (dec i)))))))

(defn dedupe-assistant-text
  "If `text` contains an unformatted/newline-stripped streaming pass immediately
   followed by a formatted pass with matching alphanumeric content, returns only
   the formatted pass. Works even when spaces, newlines, or intermediate `###`
   markers differed between the two passes."
  [text]
  (let [s (str (or text ""))]
    (if (< (count s) 72)
      s
      (let [{:keys [idxs norm]} (index-alnum s)
            norm-len (count norm)]
        (if (< norm-len 48)
          s
          (let [anchor-len (min 24 (quot norm-len 3))
                anchor (subs norm 0 anchor-len)
                search-from (max anchor-len (quot norm-len 3))
                second-norm-idx (str/index-of norm anchor search-from)]
            (if (number? second-norm-idx)
              (let [n1 (subs norm 0 second-norm-idx)
                    n2 (subs norm second-norm-idx)
                    cmp-len (min (count n1) (count n2) 64)
                    prefix-match? (and (>= cmp-len 24)
                                       (= (subs n1 0 cmp-len) (subs n2 0 cmp-len)))]
                (if prefix-match?
                  (subs s (rewind-markdown-prefix s (nth idxs second-norm-idx)))
                  s))
              s)))))))

(defn- parse-tag-attrs [attr-str]
  (into {}
        (map (fn [[_ k v]] [(keyword k) v]))
        (re-seq #"([a-zA-Z_-]+)=\"([^\"]*)\"" (str attr-str))))

(defn- extract-receipts-refined-topics
  [receipts]
  (some (fn [{:keys [tool output-preview]}]
          (when (str/includes? (str tool) "lens_refine_topics")
            (topics/parse-refined-topics output-preview)))
        (reverse (or receipts []))))

(defn- merge-grabbed-items
  [existing incoming]
  (->> (concat (or existing []) (or incoming []))
       (reduce (fn [acc item]
                 (if (some #(= (:event-id item) (:event-id %)) acc)
                   acc
                   (conj acc item)))
               [])))

(defn- extract-receipts-grabbed-context
  [receipts]
  (->> (or receipts [])
       (keep (fn [{:keys [tool output-preview]}]
               (when (str/includes? (str tool) "lens_grab_context")
                 (ctx/parse-grabbed-context output-preview))))
       (reduce merge-grabbed-items [])
       not-empty))

(defn extract-turn-artifacts
  "Extracts `<draft_message ...>...</draft_message>`, `<suggestions>...</suggestions>`,
  `<refined_topics>...</refined_topics>`, and `<grabbed_context>...</grabbed_context>`
  envelopes from assistant text, returning `{:text :suggestions :drafts :refined-topics :grabbed-context}`
  with XML envelopes (and partial streaming tails) stripped from `:text`."
  [raw-text]
  (let [s (str (or raw-text ""))
        draft-matches (vec (re-seq #"(?i)<\s*draft_message\b([^>]*)>([\s\S]*?)<\s*/\s*draft_message\s*>" s))
        drafts (->> draft-matches
                    (keep-indexed
                     (fn [idx [_ attr-str body]]
                       (let [clean-body (str/trim (str body))
                             attrs (parse-tag-attrs attr-str)]
                         (when (seq clean-body)
                           {:id (str "draft-" (inc idx))
                            :channel (:channel attrs)
                            :reply-to (:reply_to attrs)
                            :text clean-body}))))
                    vec)
        sugg-matches (vec (re-seq #"(?i)<\s*suggestions\b[^>]*>([\s\S]*?)<\s*/\s*suggestions\s*>" s))
        suggestions (->> sugg-matches
                         (mapcat (fn [[_ body]] (str/split-lines (str body))))
                         (map (fn [line]
                                (-> line
                                    str/trim
                                    (str/replace #"^(?:[-*•]|\d+[.)])\s*" "")
                                    (str/replace #"^\"|\"$" "")
                                    str/trim)))
                         (filter #(and (>= (count %) 6) (<= (count %) 160)))
                         distinct
                         (take 4)
                         vec)
        refined-topics (topics/parse-refined-topics s)
        grabbed-context (ctx/parse-grabbed-context s)
        cleaned (-> s
                    (str/replace #"(?i)<\s*draft_message\b[^>]*>[\s\S]*?<\s*/\s*draft_message\s*>\s*" "\n\n")
                    (str/replace #"(?i)<\s*refined_topics\b[^>]*>[\s\S]*?<\s*/\s*refined_topics\s*>\s*" "")
                    (str/replace #"(?i)<\s*grabbed_context\b[^>]*>[\s\S]*?<\s*/\s*grabbed_context\s*>\s*" "")
                    (str/replace #"(?i)<\s*suggestions\b[^>]*>[\s\S]*?<\s*/\s*suggestions\s*>\s*" "")
                    (str/replace #"(?i)<\s*(?:suggestions|draft_message|refined_topics|grabbed_context)\b[\s\S]*$" "")
                    str/trim)]
    (cond-> {:text cleaned
             :suggestions suggestions
             :drafts drafts}
      (seq refined-topics) (assoc :refined-topics refined-topics)
      (seq grabbed-context) (assoc :grabbed-context grabbed-context))))

(defn apply-reply
  "Applies a bot reply to the turn with request-id.
  kind is one of :accepted :token :event :done :error; data is a keyword-keyed map."
  [conversation kind request-id data]
  (case kind
    :accepted (cond-> (update-turn conversation request-id #(assoc % :status :streaming :model (:model data)))
                (not (str/blank? (:sessionId data))) (assoc :session-id (:sessionId data)))
    :token (update-turn conversation request-id
                        (fn [turn]
                          (let [raw (str (or (:raw-text turn) (:text turn) "") (:token data))
                                deduped (dedupe-assistant-text raw)
                                {:keys [text suggestions drafts refined-topics grabbed-context]} (extract-turn-artifacts deduped)
                                merged-grabbed (merge-grabbed-items (:grabbed-context turn) grabbed-context)]
                            (cond-> (assoc turn
                                           :status :streaming
                                           :raw-text raw
                                           :text text)
                              (seq suggestions) (assoc :suggestions suggestions)
                              (seq drafts) (assoc :drafts drafts)
                              (seq refined-topics) (assoc :refined-topics refined-topics)
                              (seq merged-grabbed) (assoc :grabbed-context merged-grabbed)))))
    :event (update-turn conversation request-id
                        (fn [turn]
                          (let [receipts' (fold-tool-event (:tool-receipts turn) data)
                                refined (or (when (str/includes? (str (:tool data)) "lens_refine_topics")
                                              (topics/parse-refined-topics (:preview data)))
                                            (extract-receipts-refined-topics receipts'))
                                grabbed (merge-grabbed-items
                                         (:grabbed-context turn)
                                         (or (when (str/includes? (str (:tool data)) "lens_grab_context")
                                               (ctx/parse-grabbed-context (:preview data)))
                                             (extract-receipts-grabbed-context receipts')))]
                            (cond-> (-> turn
                                        (update :events (fnil conj []) (select-keys data [:type :tool :toolCallId :preview :status :isError]))
                                        (assoc :tool-receipts receipts'))
                              (seq refined) (assoc :refined-topics refined)
                              (seq grabbed) (assoc :grabbed-context grabbed)))))
    :done (-> conversation
              (update-turn request-id (fn [turn]
                                        (let [streamed (or (:raw-text turn) (:text turn) "")
                                              candidate (if (> (count (str (:text data))) (count streamed))
                                                          (str (:text data))
                                                          streamed)
                                              deduped (dedupe-assistant-text candidate)
                                              receipts' (finalize-receipts (:tool-receipts turn) :done)
                                              {:keys [text suggestions drafts refined-topics grabbed-context]} (extract-turn-artifacts deduped)
                                              all-refined (or refined-topics
                                                              (extract-receipts-refined-topics receipts')
                                                              (:refined-topics turn))
                                              all-grabbed (-> (:grabbed-context turn)
                                                              (merge-grabbed-items (extract-receipts-grabbed-context receipts'))
                                                              (merge-grabbed-items grabbed-context))]
                                          (cond-> (-> turn
                                                      (dissoc :raw-text)
                                                      (assoc :status :done
                                                             :text text
                                                             :tool-receipts receipts'))
                                            (seq suggestions) (assoc :suggestions suggestions)
                                            (seq drafts) (assoc :drafts drafts)
                                            (seq all-refined) (assoc :refined-topics all-refined)
                                            (seq all-grabbed) (assoc :grabbed-context all-grabbed)))))
              (settle request-id))
    :error (-> conversation
               (update-turn request-id
                            (fn [turn]
                              (-> turn
                                  (dissoc :raw-text)
                                  (assoc :status :error :error (str (:error data)))
                                  (update :tool-receipts finalize-receipts :error))))
               (settle request-id))
    conversation))

;; ─────────────────────────────────────────────────────────────
;; Past-session parsing & hydration
;; ─────────────────────────────────────────────────────────────

(defn- parse-context-tag-attrs [tag]
  (parse-tag-attrs tag))

(defn- context-attrs->label [{:keys [channel kind messages event_id]}]
  (let [ch (if (str/blank? channel) "#" (str "#" (str/replace channel #"^#" "")))]
    (cond
      (= "pinned" kind) (str "📌 " ch (when (seq event_id) (str " · " (subs event_id 0 (min 8 (count event_id))))))
      (seq messages) (str ch " · " messages " msgs")
      :else ch)))

(defn strip-discord-context
  "Separates leading <discord_context ...>...</discord_context> blocks from a
  composed message or session title. Returns {:text :context :channel-id :channel-name}."
  [raw-text]
  (let [s (str/trim (str (or raw-text "")))
        open-tags (vec (re-seq #"<discord_context\b[^>]*>" s))
        attrs-list (mapv parse-context-tag-attrs open-tags)
        first-attrs (first attrs-list)
        channel-id (some-> (:channel_id first-attrs) not-empty)
        channel-name (some-> (:channel first-attrs) (str/replace #"^#" "") not-empty)
        without-blocks (-> s
                           (str/replace #"<\s*discord_context\b[^>]*>[\s\S]*?<\s*/\s*discord_context\s*>\s*" "")
                           str/trim)]
    (cond
      (and (seq open-tags) (str/blank? without-blocks))
      ;; Truncated <discord_context ...> snippet (e.g. from heuristic session title)
      (let [after-tag (-> s (str/replace #"^<\s*discord_context\b[^>]*>\s*" "") str/trim)]
        {:text (if (seq after-tag)
                 (str (if channel-name (str "#" channel-name ": ") "") after-tag)
                 (str "Session in " (if channel-name (str "#" channel-name) "channel")))
         :context (mapv context-attrs->label attrs-list)
         :channel-id channel-id
         :channel-name channel-name})

      (seq open-tags)
      {:text without-blocks
       :context (mapv context-attrs->label attrs-list)
       :channel-id channel-id
       :channel-name channel-name}

      :else
      {:text s
       :context []
       :channel-id nil
       :channel-name nil})))

(defn clean-session-title
  "Produces a single-line human-readable session title (up to 72 chars),
  stripping raw <discord_context> XML tags if present."
  [raw-title fallback]
  (let [{:keys [text]} (strip-discord-context raw-title)
        one-line (-> (if (str/blank? text) (str (or fallback "Untitled session")) text)
                     (str/replace #"\s+" " ")
                     str/trim)]
    (if (> (count one-line) 72)
      (str (subs one-line 0 71) "…")
      one-line)))

(defn conversation-title
  "Derives a display title for a conversation from its explicit :title or first user turn."
  [conversation]
  (let [first-user (some #(when (= :user (:role %)) (:text %)) (:turns conversation))]
    (clean-session-title (or (:title conversation) first-user)
                         (str "Session " (subs (str (:conversation-id conversation))
                                               0
                                               (min 8 (count (str (:conversation-id conversation)))))))))

(defn- trace-block->receipt [block]
  (when (= "tool_call" (some-> (or (:kind block) (get block "kind")) name))
    (let [status-str (str (or (:status block) "done"))
          err? (boolean (or (:isError block) (:is_error block) (= "error" status-str) (= "failed" status-str)))]
      {:id (str (or (:toolCallId block) (:tool_call_id block) (:id block) "tool"))
       :tool (str (or (:toolName block) (:tool_name block) "tool"))
       :status (cond
                 err? :error
                 (= "streaming" status-str) :running
                 :else :done)
       :input-preview (some-> (or (:inputPreview block) (:input_preview block)) str not-empty)
       :updates (vec (or (:updates block) []))
       :output-preview (some-> (or (:outputPreview block) (:output_preview block)) str not-empty)
       :is-error err?})))

(defn- system-prompt-text? [s]
  (str/starts-with? (str/trim (str s)) "You are Knoxx Lens, a reading companion"))

(defn- row-ts-ms [row]
  (let [v (:ts row)]
    (cond
      (number? v) v
      (string? v) (let [p (js/Date.parse v)] (if (js/isNaN p) 0 p))
      :else 0)))

(defn rows->turns
  "Reconstructs chronological KnoxxLens turns from Knoxx /api/memory/sessions/:id rows."
  [rows]
  (let [sorted-rows (sort-by row-ts-ms rows)
        run-groups (reduce (fn [{:keys [order by-run]} row]
                             (let [run-id (or (get-in row [:extra :run_id])
                                              (:message row)
                                              "default")]
                               {:order (if (contains? by-run run-id) order (conj order run-id))
                                :by-run (update by-run run-id (fnil conj []) row)}))
                           {:order [] :by-run {}}
                           sorted-rows)]
    (vec
     (mapcat
      (fn [run-id]
        (let [group (get-in run-groups [:by-run run-id])
              user-row (or (some #(when (and (= "user" (:role %)) (= "knoxx.message" (:kind %))) %) group)
                           (some #(when (= "user_message" (get-in % [:extra :node_type])) %) group))
              asst-row (or (some #(when (and (= "assistant" (:role %)) (= "knoxx.message" (:kind %))) %) group)
                           (some #(when (= "assistant_message" (get-in % [:extra :node_type])) %) group))
              run-row (some #(when (= "knoxx.run" (:kind %)) %) group)
              err-row (some #(when (= "knoxx.error" (:kind %)) %) group)
              raw-user-text (or (:text user-row) "")
              {:keys [text context]} (if (system-prompt-text? raw-user-text)
                                       {:text "(Earlier KnoxxLens question)" :context []}
                                       (strip-discord-context raw-user-text))
              trace-blocks (or (get-in asst-row [:extra :trace_blocks])
                               (get-in run-row [:extra :trace_blocks])
                               [])
              trace-receipts (vec (keep trace-block->receipt trace-blocks))
              folded-receipts (if (seq trace-receipts)
                                trace-receipts
                                (reduce (fn [acc row]
                                          (case (:kind row)
                                            "knoxx.tool_start" (fold-tool-event acc (assoc (:extra row) :type "tool_start" :tool (get-in row [:extra :tool_name])))
                                            "knoxx.tool_update" (fold-tool-event acc (assoc (:extra row) :type "tool_update" :tool (get-in row [:extra :tool_name])))
                                            "knoxx.tool_end" (fold-tool-event acc (assoc (:extra row) :type "tool_end" :tool (get-in row [:extra :tool_name])))
                                            acc))
                                        []
                                        group))
              asst-deduped (dedupe-assistant-text (or (:text asst-row) ""))
              {asst-text :text asst-suggestions :suggestions asst-drafts :drafts asst-refined :refined-topics asst-grabbed :grabbed-context}
              (extract-turn-artifacts asst-deduped)
              refined-topics (or asst-refined (extract-receipts-refined-topics folded-receipts))
              grabbed-context (merge-grabbed-items asst-grabbed (extract-receipts-grabbed-context folded-receipts))
              model (or (:model asst-row) (:model run-row) (get-in run-row [:extra :model]))]
          (cond-> []
            (or (seq text) asst-row)
            (conj {:role :user
                   :text (if (str/blank? text) "(Question)" text)
                   :context context})

            (or (seq asst-text) err-row (seq folded-receipts) (seq asst-drafts))
            (conj (cond-> {:role :assistant
                           :request-id (str run-id)
                           :text asst-text
                           :status (if err-row :error :done)
                           :events (mapv (fn [r] {:type "tool_end" :tool (:tool r)}) folded-receipts)
                           :tool-receipts folded-receipts}
                    (seq asst-suggestions) (assoc :suggestions asst-suggestions)
                    (seq asst-drafts) (assoc :drafts asst-drafts)
                    (seq refined-topics) (assoc :refined-topics refined-topics)
                    (seq grabbed-context) (assoc :grabbed-context grabbed-context)
                    model (assoc :model model)
                    err-row (assoc :error (:text err-row)))))))
      (:order run-groups)))))

(defn interactive-remote-session?
  "Returns true when a Knoxx session summary row represents an interactive user/lens
  session rather than a background cron/schedule/patrol trigger or autonomous
  `discord_automation` bot session."
  [row]
  (let [session-id (str (or (:session row) ""))
        title (str (or (:title row) ""))
        actor-id (str (or (:actor_id row) (:actorId row) ""))
        contract-id (str (or (:contract_id row) (:contractId row) ""))]
    (and (not (str/blank? session-id))
         (not= "discord_automation" actor-id)
         (not (str/starts-with? contract-id "ussyverse_social_"))
         (not (str/starts-with? session-id "trigger-"))
         (not (str/starts-with? session-id "translation-"))
         (not (str/starts-with? title "event: schedule/"))
         (nil? (:schedule_id row))
         (nil? (:trigger_id row)))))

(defn merge-session-summaries
  "Merges local persisted session maps and remote Knoxx /api/memory/sessions rows
  into a vector of session summary maps sorted newest-first."
  [local-sessions remote-rows]
  (let [local-by-id (into {}
                          (keep (fn [s]
                                  (when-let [cid (some-> (:conversation-id s) str not-empty)]
                                    [cid {:conversation-id cid
                                          :session-id (:session-id s)
                                          :channel-id (:channel-id s)
                                          :channel-name (:channel-name s)
                                          :title (conversation-title s)
                                          :updated-at (or (:updated-at s) "")
                                          :turn-count (count (:turns s))
                                          :source :local}])))
                          local-sessions)
        merged (reduce (fn [acc row]
                         (if-not (interactive-remote-session? row)
                           acc
                           (let [cid (str (:session row))
                                 existing (get acc cid)
                                 parsed (strip-discord-context (:title row))
                                 remote-title (clean-session-title (:title row)
                                                                   (str "Session " (subs cid 0 (min 8 (count cid)))))]
                             (assoc acc cid
                                    {:conversation-id cid
                                     :session-id (or (:session-id existing) (:active_session_id row))
                                     :channel-id (or (:channel-id existing) (:channel-id parsed))
                                     :channel-name (or (:channel-name existing) (:channel-name parsed))
                                     :title (if (and existing (seq (:title existing)) (not (str/starts-with? (:title existing) "Session ")))
                                              (:title existing)
                                              remote-title)
                                     :updated-at (or (not-empty (str (:last_ts row))) (:updated-at existing) "")
                                     :turn-count (or (when (pos? (:turn-count existing 0)) (:turn-count existing))
                                                     (:event_count row)
                                                     0)
                                     :source (if existing :both :remote)}))))
                       local-by-id
                       remote-rows)]
    (->> (vals merged)
         (sort-by #(let [t (js/Date.parse (str (:updated-at %)))]
                     (if (js/isNaN t) 0 (- t))))
         vec)))
