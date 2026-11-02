(ns bitch-tracker.domain.lens-topics
  "Pure noun and proper-noun topic extraction, BERT WordPiece NER aggregation,
   Penn Treebank POS noun filtering, synchronous fallback heuristics, and
   `lens_refine_topics` receipt parsing for KnoxxLens. Zero I/O."
  (:require [clojure.string :as str]))

(def ^:private url-pattern
  #"https?://[^\s<>()]+")

(def ^:private noun-pos-tags
  #{"NN" "NNS" "NNP" "NNPS"})

(def ^:private proper-noun-pos-tags
  #{"NNP" "NNPS"})

(def ^:private banned-terms
  "Verbs, prepositions, adjectives, adverbs, pronouns, Discord chrome, and
   high-frequency conversational filler nouns that pollute topic chips."
  #{"a" "about" "access" "actually" "add" "added" "adding" "after" "again" "all"
    "almost" "along" "already" "also" "always" "am" "among" "an" "and" "another"
    "any" "anyone" "anything" "anyway" "are" "around" "as" "ask" "asked" "asking"
    "at" "attached" "attachment" "attachments" "available" "away" "awesome"
    "back" "bad" "base" "based" "basically" "be" "became" "because" "become"
    "been" "before" "being" "best" "better" "between" "big" "bit" "both" "bring"
    "brought" "build" "building" "built" "bunch" "but" "by" "call" "called"
    "calling" "came" "can" "cannot" "cant" "case" "cases" "cdn" "change"
    "changed" "changes" "changing" "channel" "channels" "chat" "check" "checked"
    "checking" "clear" "click" "close" "com" "come" "comes" "coming" "cool"
    "could" "couple" "create" "created" "creating" "current" "currently" "day"
    "days" "deal" "definitely" "did" "didnt" "different" "digest" "discord"
    "discordapp" "discuss" "discussed" "discussing" "discussion" "do" "does"
    "doesnt" "doing" "done" "dont" "down" "each" "earlier" "early" "easier"
    "easy" "edit" "edited" "either" "else" "end" "ended" "enough" "etc" "even"
    "ever" "every" "everyone" "everything" "exact" "exactly" "example" "extra"
    "facinating" "fact" "far" "fascinating" "fast" "feel" "feeling" "feels"
    "felt" "few" "file" "files" "find" "finding" "fine" "first" "fix" "fixed"
    "fixes" "fixing" "folks" "for" "found" "free" "from" "full" "fun" "further"
    "gave" "general" "get" "gets" "getting" "gif" "give" "given" "gives"
    "giving" "glad" "global" "go" "goes" "going" "gone" "gonna" "good" "got"
    "gotta" "great" "guess" "guy" "guys" "had" "hand" "happen" "happened"
    "happening" "happens" "hard" "has" "have" "having" "he" "head" "hear"
    "heard" "hell" "hello" "help" "helped" "helping" "helps" "her" "here" "hey"
    "hi" "high" "him" "his" "hit" "honestly" "hope" "hour" "hours" "how" "http"
    "https" "huge" "i" "idea" "ideas" "if" "im" "image" "images" "important"
    "in" "info" "instead" "interesting" "into" "is" "isnt" "issue" "issues" "it"
    "item" "items" "its" "itself" "ive" "jpeg" "jpg" "just" "keep" "keeping"
    "kept" "kind" "kinda" "kinds" "knew" "know" "knowing" "known" "knows"
    "large" "last" "late" "later" "least" "leave" "left" "less" "let" "lets"
    "level" "like" "liked" "likely" "line" "lines" "link" "links" "list"
    "little" "live" "lmao" "local" "lol" "long" "look" "looked" "looking"
    "looks" "lost" "lot" "lots" "love" "made" "main" "major" "make" "makes"
    "making" "man" "many" "matter" "may" "maybe" "me" "mean" "meaning" "means"
    "meant" "media" "message" "messages" "might" "min" "mine" "minute" "minutes"
    "mode" "moment" "month" "months" "more" "most" "mostly" "mov" "move" "moved"
    "moving" "mp3" "mp4" "much" "must" "my" "myself" "name" "named" "near"
    "need" "needed" "needing" "needs" "net" "never" "new" "next" "nice" "no"
    "none" "nope" "normal" "not" "note" "notes" "nothing" "now" "number" "of"
    "off" "often" "ogg" "oh" "ok" "okay" "old" "on" "once" "one" "ones" "only"
    "open" "opened" "opening" "or" "org" "other" "others" "our" "ours" "out"
    "over" "own" "page" "part" "parts" "past" "pdf" "people" "per" "person"
    "pick" "picked" "piece" "place" "places" "plan" "planned" "planning" "plans"
    "play" "played" "playing" "please" "plus" "png" "point" "points" "possible"
    "post" "posted" "posting" "posts" "pretty" "probably" "problem" "problems"
    "pull" "pulled" "push" "pushed" "put" "putting" "question" "questions"
    "quick" "quite" "rather" "re" "read" "reading" "ready" "real" "really"
    "reason" "reasons" "recent" "recently" "reply" "right" "rip" "room" "run"
    "running" "runs" "said" "same" "saw" "say" "saying" "says" "screen"
    "screenshot" "screenshots" "sec" "second" "seconds" "see" "seeing" "seem"
    "seemed" "seems" "seen" "send" "sending" "sense" "sent" "set" "sets"
    "setting" "she" "should" "shouldnt" "show" "showed" "showing" "shown"
    "shows" "side" "since" "site" "small" "so" "some" "somebody" "someone"
    "something" "sometimes" "somewhere" "soon" "sort" "sound" "sounds" "start"
    "started" "starting" "starts" "state" "stay" "still" "stop" "stopped"
    "stuff" "such" "summarize" "summary" "super" "sure" "svg" "system" "take"
    "taken" "takes" "taking" "talk" "talked" "talking" "tell" "telling" "test"
    "text" "than" "thank" "thanks" "that" "thats" "the" "their" "theirs" "them"
    "then" "there" "theres" "these" "they" "theyre" "thing" "things" "think"
    "thinking" "thinks" "this" "those" "though" "thought" "thoughts" "through"
    "time" "times" "to" "today" "together" "told" "tonight" "too" "took" "top"
    "total" "totally" "tried" "true" "truly" "try" "trying" "turn" "turned"
    "turns" "two" "type" "types" "uh" "um" "under" " unknown" "unknown" "until"
    "up" "update" "updated" "updates" "updating" "upon" "us" "use" "used"
    "useful" "user" "users" "uses" "using" "usually" "ve" "version" "very"
    "via" "view" "wait" "waiting" "want" "wanted" "wanting" "wants" "was"
    "wasnt" "watch" "watching" "wav" "way" "ways" "we" "webm" "webp" "website"
    "week" "weeks" "well" "went" "were" "what" "whatever" "whats" "when" "where"
    "whether" "which" "while" "who" "whole" "whom" "whose" "why" "will" "with"
    "within" "without" "wonder" "wondering" "word" "words" "work" "worked"
    "working" "works" "world" "would" "wouldnt" "wtf" "yeah" "year" "years"
    "yep" "yes" "yet" "you" "your" "youre" "yours"})

(def ^:private allowed-ing-nouns
  #{"accounting" "Alerting" "alerting" "authoring" "backing" "benchmarking"
    "billing" "binding" "bindings" "bootstrapping" "branching" "broadcasting"
    "buffering" "bundling" "caching" "ceilings" "chunking" "clustering" "coding"
    "compaction" "computing" "conditioning" "config" "counseling" "debugging"
    "decoding" "deduplication" "embedding" "embeddings" "encoding" "engineering"
    "eventing" "farming" "federation" "filtering" "fine-tuning" "finetuning"
    "flooring" "formatting" "forwarding" "framing" "funding" "gaming"
    "gardening" "grounding" "grouping" "hashing" "heading" "headings" "healing"
    "hearing" "heating" "hosting" "housing" "imaging" "indexing" "keybinding"
    "keybindings" "labeling" "landing" "learning" "licensing" "lighting"
    "linking" "linting" "listing" "loading" "locking" "logging" "mapping"
    "marketing" "masking" "matching" "meeting" "meetings" "messaging" "metering"
    "mining" "mirroring" "modeling" "monitoring" "mounting" "networking"
    "onboarding" "packaging" "padding" "paging" "pairing" "marshaling"
    "parsing" "partitioning" "patching" "pathing" "peering" "ping" "pipelining"
    "planning" "plumbing" "podcasting" "polling" "pooling" "positioning"
    "preprocessing" "pricing" "printing" "processing" "profiling" "programming"
    "prompting" "provisioning" "publishing" " querying" "queuing" "ranking"
    "rating" "reasoning" "recording" "recordings" "refactoring" "rendering"
    "reporting" "reranking" "ring" "routing" "sampling" "sandbox" "sandboxing"
    "scaffolding" "scaling" "scanning" "scheduling" "scoring" "scraping"
    "scripting" "searching" "seeding" "sequencing" "setting" "settings"
    "shading" "sharding" "shipping" "shopping" "signaling" "singing" "slotting"
    "smoothing" "sorting" "spacing" "spanning" "spawning" "spelling" "splitting"
    "spoofing" "staging" "streaming" "string" "strings" "styling" "switching"
    "syncing" "tagging" "targeting" "testing" "theming" "threading"
    "throttling" "tiling" "timing" "tokenization" "tooling" "tracing"
    "tracking" "trading" "training" "transcoding" "tunneling" "tuning" "typing"
    "unboxing" "versioning" "warning" "warnings" "wedding" "weighting" "wing"
    "wiring" "writing"})

(def ^:private non-noun-suffixes
  ["ingly" "edly" "able" "ible" "ious" "eous" "less" "ness" "fully" "ively" "ating" "izing" "ifying"])

(defn clean-text-for-nlp
  "Strips URLs, raw Discord snowflake mentions, custom emoji tags, and attachment
   placeholders while preserving sentence structure and capitalization for NER + POS."
  [text]
  (-> (str (or text ""))
      (str/replace url-pattern " ")
      (str/replace #"<a?:([a-zA-Z0-9_]+):\d+>" " ")
      (str/replace #"<(?:@!?|@&|#)\d+>" " ")
      (str/replace #"\[attached [^\]]+\]" " ")
      (str/replace #"```[\s\S]*?```" " ")
      (str/replace #"`([^`\n]{1,48})`" " $1 ")
      (str/replace #"\s+" " ")
      str/trim))

(defn- clean-term
  [s]
  (some-> s
          str
          (str/replace #"^[#@\"'`(\[{<*_-]+|[.,!?;:\"'`)\]}>*_-]+$" "")
          (str/replace #"\s+" " ")
          str/trim
          not-empty))

(defn- suspect-non-noun-morphology?
  [lower]
  (or (and (str/ends-with? lower "ly") (> (count lower) 4))
      (and (str/ends-with? lower "ing")
           (not (contains? allowed-ing-nouns lower)))
      (and (str/ends-with? lower "ed")
           (> (count lower) 4)
           (not (contains? #{"bed" "cred" "embed" "fed" "led" "med" "red" "seed" "shed" "sled" "speed" "steed" "thread" "weed"} lower)))
      (some #(and (> (count lower) (+ 3 (count %)))
                  (not= "ness" %)
                  (str/ends-with? lower %))
            non-noun-suffixes)))

(defn valid-topic-candidate?
  "Returns true when `term` is a clean noun, noun compound, or proper noun and
   not a verb, adjective, preposition, hex/numeric id, or conversational filler."
  ([term] (valid-topic-candidate? term nil))
  ([term {:keys [proper? ner?]}]
   (when-let [t (clean-term term)]
     (let [words (str/split t #"\s+")
           lowers (mapv str/lower-case words)
           single (first lowers)]
       (and (<= 1 (count words) 4)
            (<= 3 (count t) 44)
            (boolean (re-find #"[A-Za-z]" t))
            (not (str/starts-with? (str/lower-case t) "http"))
            (not (re-matches #"^\d+$" t))
            (not (re-matches #"^[0-9a-fA-F-]{8,}$" t))
            (if (= 1 (count words))
              (and (not (contains? banned-terms single))
                   (or ner?
                       (and proper?
                            (boolean (re-find #"[A-Z]" (subs t 1)))
                            (not (str/ends-with? single "ing")))
                       (not (suspect-non-noun-morphology? single))))
              (and (not-every? #(contains? banned-terms %) lowers)
                   (not (contains? banned-terms (first lowers)))
                   (not (contains? banned-terms (last lowers)))
                   (or ner? (not (suspect-non-noun-morphology? (last lowers)))))))))))

(defn- entity-group
  [entity-label]
  (-> (str (or entity-label ""))
      (str/replace #"^[BI]-" "")
      str/trim
      str/upper-case))

(defn- join-ner-piece
  [prev-text word]
  (cond
    (str/starts-with? word "##")
    (str prev-text (subs word 2))

    (or (empty? prev-text)
        (re-find #"[-/.@_]$" prev-text)
        (re-find #"^[-/.@_]" word))
    (str prev-text word)

    :else
    (str prev-text " " word)))

(defn aggregate-ner-tokens
  "Aggregates raw BERT WordPiece token-classification outputs
   (`[{:entity \"B-ORG\" :score 0.99 :index 7 :word \"Open\"} {:entity \"I-ORG\" :index 8 :word \"##Planner\"}]`)
   into complete entity spans `[{:term \"OpenPlanner\" :entity \"ORG\" :score 0.96}]`."
  [tokens]
  (let [step (fn [{:keys [spans current]} raw]
               (let [word (str (or (:word raw) ""))
                     label (str (or (:entity raw) (:entity_group raw) ""))
                     group (entity-group label)
                     score (double (or (:score raw) 0.0))
                     idx (:index raw)
                     subword? (str/starts-with? word "##")
                     b-tag? (str/starts-with? label "B-")
                     adjacent? (and current
                                    (number? idx)
                                    (number? (:last-index current))
                                    (= idx (inc (:last-index current))))
                     continue? (and current
                                    adjacent?
                                    (or subword?
                                        (and (not b-tag?) (= group (:entity current)))))]
                 (cond
                   (or (str/blank? word) (= "O" group) (str/blank? group))
                   {:spans (cond-> spans current (conj current))
                    :current nil}

                   continue?
                   {:spans spans
                    :current (-> current
                                 (update :term join-ner-piece word)
                                 (update :scores conj score)
                                 (assoc :last-index idx))}

                   :else
                   {:spans (cond-> spans current (conj current))
                    :current {:term (if subword? (subs word 2) word)
                              :entity group
                              :scores [score]
                              :last-index idx}})))
        {:keys [spans current]} (reduce step {:spans [] :current nil} tokens)
        all-spans (cond-> spans current (conj current))]
    (->> all-spans
         (keep (fn [{:keys [term entity scores]}]
                 (let [avg-score (if (seq scores) (/ (reduce + 0.0 scores) (count scores)) 0.0)
                       cleaned (clean-term term)]
                   (when (and cleaned
                              (>= avg-score 0.72)
                              (valid-topic-candidate? cleaned {:proper? true :ner? true}))
                     {:term cleaned
                      :kind "proper_noun"
                      :category (case entity
                                  "PER" "person"
                                  "ORG" "org"
                                  "LOC" "location"
                                  "entity")
                      :score avg-score
                      :source :ner}))))
         vec)))

(defn extract-pos-noun-candidates
  "Extracts noun (`NN`, `NNS`), proper-noun (`NNP`, `NNPS`), and adjacent 2-word
   proper-noun / noun-compound candidates from a `wink-pos-tagger` tagged token
   sequence (`[{:value \"OpenPlanner\" :tag \"word\" :pos \"NNP\"} ...]`)."
  [tagged-tokens]
  (let [words (->> tagged-tokens
                   (filter (fn [{:keys [tag value]}]
                             (and (not= "punctuation" (str tag))
                                  (not= "url" (str tag))
                                  (not= "email" (str tag))
                                  (seq (str/trim (str value))))))
                   vec)
        n (count words)
        singles (keep (fn [{:keys [value pos]}]
                        (let [pos-str (str pos)
                              proper? (contains? proper-noun-pos-tags pos-str)]
                          (when (and (contains? noun-pos-tags pos-str)
                                     (valid-topic-candidate? value {:proper? proper?}))
                            {:term (clean-term value)
                             :kind (if proper? "proper_noun" "noun")
                             :category (if proper? "entity" "concept")
                             :score (if proper? 1.35 1.0)
                             :source :pos})))
                      words)
        compounds (keep (fn [i]
                          (let [w1 (nth words i)
                                w2 (nth words (inc i))
                                p1 (str (:pos w1))
                                p2 (str (:pos w2))
                                both-proper? (and (contains? proper-noun-pos-tags p1)
                                                  (contains? proper-noun-pos-tags p2))
                                noun-pair? (and (contains? noun-pos-tags p1)
                                                (contains? noun-pos-tags p2))
                                phrase (str (clean-term (:value w1)) " " (clean-term (:value w2)))]
                            (when (and noun-pair?
                                       (valid-topic-candidate? (:value w1) {:proper? (contains? proper-noun-pos-tags p1)})
                                       (valid-topic-candidate? (:value w2) {:proper? (contains? proper-noun-pos-tags p2)})
                                       (valid-topic-candidate? phrase {:proper? both-proper?}))
                              {:term phrase
                               :kind (if both-proper? "proper_noun" "noun")
                               :category (if both-proper? "entity" "concept")
                               :score (if both-proper? 1.65 1.15)
                               :source :pos-compound})))
                        (range 0 (max 0 (dec n))))]
    (vec (concat compounds singles))))

(defn- normalize-stem
  [lower-term]
  (cond
    (and (> (count lower-term) 5) (str/ends-with? lower-term "ies"))
    (str (subs lower-term 0 (- (count lower-term) 3)) "y")

    (and (> (count lower-term) 4)
         (str/ends-with? lower-term "s")
         (not (str/ends-with? lower-term "ss"))
         (not (str/ends-with? lower-term "us"))
         (not (str/ends-with? lower-term "is")))
    (subs lower-term 0 (dec (count lower-term)))

    :else
    lower-term))

(defn- prefer-display-term
  [existing candidate]
  (let [upper-count (fn [s] (count (re-seq #"[A-Z]" (str s))))]
    (cond
      (nil? existing) candidate
      (> (upper-count candidate) (upper-count existing)) candidate
      (< (count candidate) (count existing)) candidate
      :else existing)))

(defn rank-topics
  "Combines NER spans and POS noun candidates across messages, filters out bare
   speaker handles unless discussed by multiple messages, consolidates singular/plural
   variants, and returns up to `max-topics` ranked maps `{:term :kind :category :count :score}`."
  ([candidates]
   (rank-topics candidates {}))
  ([candidates {:keys [participants max-topics min-noun-count]
                :or {max-topics 10 min-noun-count 2}}]
   (let [speaker-set (->> (or participants [])
                          (map #(str/lower-case (str (if (map? %) (:author %) %))))
                          (remove str/blank?)
                          set)
         grouped (reduce
                  (fn [acc {:keys [term kind category score source]}]
                    (if-let [cleaned (clean-term term)]
                      (let [lower (str/lower-case cleaned)
                            group-key (if (= "proper_noun" kind) lower (normalize-stem lower))
                            prev (get acc group-key {:term nil
                                                     :kind "noun"
                                                     :category "concept"
                                                     :hits 0
                                                     :ner-hits 0
                                                     :compound? false
                                                     :raw-score 0.0})]
                        (assoc acc group-key
                               {:term (prefer-display-term (:term prev) cleaned)
                                :kind (if (or (= "proper_noun" kind) (= "proper_noun" (:kind prev)))
                                        "proper_noun"
                                        "noun")
                                :category (if (not= "concept" (or category "concept"))
                                            category
                                            (:category prev))
                                :hits (inc (:hits prev))
                                :ner-hits (+ (:ner-hits prev) (if (= :ner source) 1 0))
                                :compound? (or (:compound? prev) (= :pos-compound source) (str/includes? cleaned " "))
                                :raw-score (+ (:raw-score prev) (double (or score 1.0)))}))
                      acc))
                  {}
                  candidates)]
     (->> (vals grouped)
          (remove (fn [{:keys [term hits]}]
                    (and (contains? speaker-set (str/lower-case term))
                         (< hits 3))))
          (filter (fn [{:keys [kind hits ner-hits compound?]}]
                    (or (pos? ner-hits)
                        (= "proper_noun" kind)
                        (and compound? (>= hits 2))
                        (>= hits min-noun-count))))
          (map (fn [{:keys [term kind category hits ner-hits raw-score]}]
                 {:term term
                  :kind kind
                  :category category
                  :count hits
                  :score (/ (Math/round (* (+ raw-score (* 0.9 ner-hits)) 100.0)) 100.0)}))
          (sort-by (fn [{:keys [score kind] topic-count :count}]
                     [(- score) (- topic-count) (if (= "proper_noun" kind) 0 1)]))
          (take max-topics)
          vec))))

(defn extract-fallback-noun-terms
  "Fast synchronous client-side noun & proper-noun candidate extractor used for
   search query terms and pre-bot-response Channel Map fallback."
  [text]
  (let [cleaned (clean-text-for-nlp text)
        sent-initials (set (map second (re-seq #"(?:^|[.!?]\s+)([A-Z][a-z]+)\b" cleaned)))
        tokens (re-seq #"[A-Za-z][A-Za-z0-9_./-]{2,}" cleaned)]
    (->> tokens
         (keep (fn [tok]
                 (let [term (clean-term tok)
                       has-mid-cap? (and term (> (count term) 1) (boolean (re-find #"[A-Z]" (subs term 1))))
                       tech-ident? (and term (boolean (re-find #"[_/-]|\.[a-zA-Z]" term)))
                       title-case? (and term
                                        (boolean (re-matches #"^[A-Z][a-zA-Z0-9]+$" term))
                                        (not (contains? sent-initials term)))
                       proper? (or has-mid-cap? tech-ident? title-case?)]
                   (when (and term (valid-topic-candidate? term {:proper? proper?}))
                     {:term (if proper? term (str/lower-case term))
                      :kind (if proper? "proper_noun" "noun")
                      :category (if proper? "entity" "concept")
                      :score (cond
                               has-mid-cap? 1.6
                               tech-ident? 1.45
                               title-case? 1.35
                               :else 1.0)
                      :source :fallback}))))
         vec)))

(defn extract-fallback-topics
  "Synchronous pure-CLJS fallback topic ranker over channel messages before
   the bot's neural NER + Penn Treebank POS response arrives."
  [messages]
  (let [n (count messages)
        participants (distinct (keep :author messages))
        candidates (mapcat #(extract-fallback-noun-terms (:text %)) messages)
        min-cnt (if (<= n 4) 1 2)]
    (rank-topics candidates {:participants participants
                             :max-topics 10
                             :min-noun-count min-cnt})))

(defn parse-refined-topics
  "Extracts refined topics from `<refined_topics>{...}</refined_topics>` envelopes
   in tool receipts or assistant text. Returns vector of topic maps or nil."
  [raw-text]
  (when-let [[_ payload] (re-find #"(?i)<\s*refined_topics\s*>([\s\S]*?)<\s*/\s*refined_topics\s*>"
                                  (str (or raw-text "")))]
    (try
      (let [parsed (js->clj (.parse js/JSON (str/trim payload)) :keywordize-keys true)
            topics (or (:topics parsed) [])]
        (when (seq topics)
          (->> topics
               (keep (fn [item]
                       (let [term (clean-term (if (map? item) (:term item) item))
                             kind (if (map? item) (or (:kind item) "noun") "noun")]
                         (when (and term (valid-topic-candidate? term {:proper? (= "proper_noun" kind) :ner? true}))
                           {:term term
                            :kind kind
                            :category (or (when (map? item) (:category item)) "concept")
                            :score (double (or (when (map? item) (:salience item)) 1.0))}))))
               vec
               not-empty)))
      (catch :default _
        nil))))
