(ns bitch-tracker.bot.topics
  "Bot-side neural NER (`@huggingface/transformers` `Xenova/bert-base-NER`) and
   Penn Treebank POS tagger (`wink-pos-tagger`) service for KnoxxLens channel
   topic extraction. Runs in Node (`bitch-tracker-bot`), never bundled into the
   BetterDiscord browser plugin."
  (:require ["@huggingface/transformers" :as transformers]
            ["wink-pos-tagger" :as wink-pos-tagger]
            [bitch-tracker.domain.lens-topics :as topics]
            [clojure.string :as str]))

(def ^:private ner-model-id
  "Xenova/bert-base-NER")

(def ^:private max-cache-entries 64)

(defonce ^:private pos-tagger* (atom nil))
(defonce ^:private ner-pipeline-promise* (atom nil))
(defonce ^:private ner-unavailable?* (atom false))
(defonce ^:private topic-cache* (atom {:order [] :entries {}}))

(defn- ensure-pos-tagger! []
  (or @pos-tagger*
      (let [instance (wink-pos-tagger)]
        (reset! pos-tagger* instance)
        instance)))

(defn- ^:async load-ner-pipeline! []
  (try
    (let [pipeline-fn (.-pipeline transformers)
          pipe (await (pipeline-fn "token-classification" ner-model-id #js {:dtype "q8"}))]
      pipe)
    (catch :default err
      (reset! ner-unavailable?* true)
      (js/console.warn "[bot/topics] Neural NER unavailable, falling back to Penn Treebank POS tagger:" (.-message err))
      nil)))

(defn- ^:async ensure-ner-pipeline! []
  (when-not @ner-unavailable?*
    (if-let [p @ner-pipeline-promise*]
      (await p)
      (let [p (load-ner-pipeline!)]
        (reset! ner-pipeline-promise* p)
        (await p)))))

(defn- cache-get [k]
  (get-in @topic-cache* [:entries k]))

(defn- cache-put! [k v]
  (swap! topic-cache*
         (fn [{:keys [order entries]}]
           (let [order' (conj (vec (remove #(= % k) order)) k)
                 overflow (max 0 (- (count order') max-cache-entries))
                 dropped (take overflow order')
                 kept-order (vec (drop overflow order'))
                 entries' (-> (apply dissoc entries dropped)
                              (assoc k v))]
             {:order kept-order :entries entries'})))
  v)

(defn- batch-texts
  "Batches cleaned message strings into chunks <= 1500 chars (~320 tokens) so
   BERT's 512-WordPiece window never truncates mid-buffer."
  [texts]
  (reduce
   (fn [batches line]
     (let [cur (peek batches)]
       (if (and cur (<= (+ (count cur) 2 (count line)) 1500))
         (conj (pop batches) (str cur ". " line))
         (conj batches line))))
   []
   (take-last 80 texts)))

(defn- tag-sentence-pos
  [^js tagger text]
  (try
    (let [raw (.tagSentence tagger text)]
      (mapv (fn [^js tok]
              {:value (.-value tok)
               :tag (.-tag tok)
               :pos (.-pos tok)})
            (array-seq raw)))
    (catch :default _
      [])))

(defn- ^:async run-ner-batches!
  [ner-pipe batches]
  (if-not ner-pipe
    []
    (loop [remaining batches
           acc []]
      (if-let [text-chunk (first remaining)]
        (let [spans (try
                      (let [raw (await (ner-pipe text-chunk #js {:ignore_labels #js ["O"]}))
                            tokens (js->clj raw :keywordize-keys true)]
                        (topics/aggregate-ner-tokens tokens))
                      (catch :default _
                        []))]
          (recur (rest remaining) (into acc spans)))
        acc))))

(defn ^:async extract-channel-topics!
  "Extracts ranked noun & proper-noun topics for a Discord channel message slice
   using `Xenova/bert-base-NER` + `wink-pos-tagger`."
  [{:keys [channel-id fingerprint messages max-topics]}]
  (let [cache-key (when (and (seq (str channel-id)) (seq (str fingerprint)))
                    (str channel-id ":" fingerprint))]
    (if-let [cached (and cache-key (cache-get cache-key))]
      cached
      (let [cleaned-lines (->> messages
                               (map #(topics/clean-text-for-nlp (:text %)))
                               (remove str/blank?)
                               vec)
            participants (->> messages (keep :author) distinct vec)
            tagger (ensure-pos-tagger!)
            pos-candidates (mapcat #(topics/extract-pos-noun-candidates (tag-sentence-pos tagger %))
                                   cleaned-lines)
            ner-pipe (await (ensure-ner-pipeline!))
            ner-candidates (await (run-ner-batches! ner-pipe (batch-texts cleaned-lines)))
            min-cnt (if (<= (count cleaned-lines) 4) 1 2)
            ranked (topics/rank-topics (into (vec ner-candidates) pos-candidates)
                                       {:participants participants
                                        :max-topics (or max-topics 10)
                                        :min-noun-count min-cnt})
            result {:ok true
                    :channelId (str (or channel-id ""))
                    :fingerprint (str (or fingerprint ""))
                    :engine (if ner-pipe
                              (str ner-model-id "+wink-pos-tagger")
                              "wink-pos-tagger")
                    :topics ranked}]
        (when cache-key
          (cache-put! cache-key result))
        result))))
