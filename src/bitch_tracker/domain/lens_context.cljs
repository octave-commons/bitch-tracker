(ns bitch-tracker.domain.lens-context
  "KnoxxLens context selection: which Discord messages accompany a question.
  No I/O. Inputs and outputs are plain maps; Discord JS objects are converted
  at the plugin edge through the shared OpenPlanner event builder, so every
  context message carries its canonical OpenPlanner event id."
  (:require [clojure.string :as str]))

(def default-tail-limit
  "Number of most-recent channel messages included when the tail chip is on."
  50)

(def max-tail-limit
  "Upper bound for the channel-tail chip."
  200)

(def default-budget-chars
  "Character budget for all rendered context in one turn (~4 chars per token)."
  16000)

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
;; Context messages
;; ─────────────────────────────────────────────────────────────

(defn event->context-message
  "Projects an OpenPlanner discord.message event map (keyword keys) onto a context message."
  [event]
  (let [extra (:extra event)
        event-meta (:meta event)]
    {:event-id (str (:id event))
     :message-id (str (:message_id extra))
     :channel-id (str (:channel_id extra))
     :guild-id (str (:guild_id extra))
     :ts (str (:ts event))
     :author (str (or (:author_global_name event-meta) (:author event-meta) (:author_id event-meta) "unknown"))
     :author-id (str (:author_id event-meta))
     :text (str (or (:text event) ""))
     :attachments (->> (:attachments extra) (keep :url) (map str) vec)}))

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

(defn render-message
  "Renders one context message as a single transcript line."
  [{:keys [ts author text attachments]}]
  (let [body (str/trim (defang-tags (str/replace (str text) #"\s*\n\s*" " ⏎ ")))
        files (when (seq attachments) (str " [attachments: " (str/join " " attachments) "]"))]
    (str "[" (minute-stamp ts) "] " (defang-tags author) ": " body files)))

;; ─────────────────────────────────────────────────────────────
;; Selection (per channel)
;; ─────────────────────────────────────────────────────────────

(defn empty-selection
  "Returns the default selection for a channel: tail chip on, nothing pinned."
  [channel]
  {:channel channel
   :tail-enabled? true
   :tail-limit default-tail-limit
   :pinned []})

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

(defn resolve-context
  "Turns a selection plus the channel's loaded messages into rendered context items.
  Pinned messages are explicit and always included; the channel tail fills the
  remaining budget newest-first and skips messages that are already pinned.
  Returns {:items [{:kind :label :text :event-ids}] :chars n :dropped n :over-budget? bool}."
  ([selection tail-messages] (resolve-context selection tail-messages default-budget-chars))
  ([{:keys [channel pinned tail-enabled? tail-limit]} tail-messages budget-chars]
   (let [channel-attrs [[:guild (:guild-name channel)] [:channel (str "#" (:name channel))] [:channel_id (:id channel)]]
         pinned-lines (mapv render-message pinned)
         pinned-chars (reduce + 0 (map (comp inc count) pinned-lines))
         pinned-item (when (seq pinned)
                       {:kind :pinned
                        :label (str (count pinned) " pinned message" (when (not= 1 (count pinned)) "s"))
                        :text (block (conj channel-attrs [:kind "pinned"]) pinned-lines)
                        :event-ids (mapv :event-id pinned)})
         pinned-ids (set (map :event-id pinned))
         candidates (when tail-enabled?
                      (->> tail-messages
                           (remove #(contains? pinned-ids (:event-id %)))
                           (take-last tail-limit)))
         {:keys [messages char-count dropped]} (take-tail-within candidates (max 0 (- budget-chars pinned-chars)))
         tail-item (when (seq messages)
                     {:kind :channel-tail
                      :label (str "#" (:name channel) " · last " (count messages))
                      :text (block (conj channel-attrs [:kind "channel-tail"] [:messages (count messages)])
                                   (mapv render-message messages))
                      :event-ids (mapv :event-id messages)})]
     {:items (vec (remove nil? [pinned-item tail-item]))
      :chars (+ pinned-chars char-count)
      :dropped dropped
      :over-budget? (> pinned-chars budget-chars)})))
