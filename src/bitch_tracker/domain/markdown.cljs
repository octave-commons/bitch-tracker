(ns bitch-tracker.domain.markdown
  "Pure Markdown parser for KnoxxLens assistant and user turns.
  Produces a data AST of block and inline nodes with zero DOM or I/O dependencies."
  (:require [clojure.string :as str]))

(def ^:private inline-token-re
  #"(`[^`\n]+`|\*\*\*[^*\n]+\*\*\*|\*\*[^*\n]+\*\*|__[^_\n]+__|~~[^~\n]+~~|\*[^*\s][^*\n]*\*|\*[^*\s]\*|\[[^\]\n]+\]\(https?://[^\s)]+\)|https?://[^\s<>()]+)")

(defn- clean-trailing-url-punct [url]
  (let [trimmed (str/replace (str url) #"[.,;:!?]+$" "")]
    {:url trimmed
     :suffix (subs (str url) (count trimmed))}))

(defn parse-inline
  "Parses inline Markdown formatting (code, bold, italic, strikethrough, links)
  into a vector of AST maps."
  [text]
  (let [s (str (or text ""))]
    (if (str/blank? s)
      (if (empty? s) [] [{:type :text :text s}])
      (loop [remaining s
             acc []]
        (if (empty? remaining)
          acc
          (if-let [m (re-find inline-token-re remaining)]
            (let [token (first m)
                  idx (str/index-of remaining token)
                  before (subs remaining 0 idx)
                  after (subs remaining (+ idx (count token)))
                  acc' (cond-> acc
                         (pos? (count before)) (conj {:type :text :text before}))
                  node (cond
                         (and (str/starts-with? token "`") (str/ends-with? token "`"))
                         {:type :code :text (subs token 1 (dec (count token)))}

                         (and (str/starts-with? token "***") (str/ends-with? token "***"))
                         {:type :bold-italic :children (parse-inline (subs token 3 (- (count token) 3)))}

                         (or (and (str/starts-with? token "**") (str/ends-with? token "**"))
                             (and (str/starts-with? token "__") (str/ends-with? token "__")))
                         {:type :bold :children (parse-inline (subs token 2 (- (count token) 2)))}

                         (and (str/starts-with? token "~~") (str/ends-with? token "~~"))
                         {:type :strike :children (parse-inline (subs token 2 (- (count token) 2)))}

                         (and (str/starts-with? token "*") (str/ends-with? token "*"))
                         {:type :italic :children (parse-inline (subs token 1 (dec (count token))))}

                         (str/starts-with? token "[")
                         (let [[_ label href] (re-matches #"\[([^\]\n]+)\]\((https?://[^\s)]+)\)" token)]
                           {:type :link :label label :href href})

                         :else
                         (let [{:keys [url suffix]} (clean-trailing-url-punct token)]
                           (if (seq suffix)
                             [:multi
                              {:type :link :label url :href url}
                              {:type :text :text suffix}]
                             {:type :link :label url :href url})))]
              (recur after
                     (if (and (vector? node) (= :multi (first node)))
                       (into acc' (rest node))
                       (conj acc' node))))
            (conj acc {:type :text :text remaining})))))))

(defn- heading-line [line]
  (when-let [[_ hashes body] (re-matches #"^\s*(#{1,6})\s*(.+?)\s*$" line)]
    {:type :heading
     :level (count hashes)
     :children (parse-inline body)}))

(defn- hr-line? [line]
  (boolean (re-matches #"^\s*(?:-\s*-\s*-[- \t]*|\*\s*\*\s*\*[* \t]*|_\s*_\s*_[_ \t]*)$" line)))

(defn- fence-open [line]
  (when-let [[_ ticks lang] (re-matches #"^\s*(`{3,}|~{3,})\s*([A-Za-z0-9_+.-]*)\s*$" line)]
    {:fence ticks :lang (str/lower-case (or lang ""))}))

(defn- fence-close? [line fence]
  (boolean (and fence (re-matches (re-pattern (str "^\\s*" fence "\\s*$")) line))))

(defn- ul-item [line]
  (when-let [[_ indent body] (re-matches #"^(\s*)(?:[-*+•])\s+(.+)$" line)]
    {:indent (count indent)
     :children (parse-inline body)}))

(defn- ol-item [line]
  (when-let [[_ indent num body] (re-matches #"^(\s*)(\d+)[.)]\s+(.+)$" line)]
    {:indent (count indent)
     :index (js/parseInt num 10)
     :children (parse-inline body)}))

(defn- blockquote-line [line]
  (when-let [[_ body] (re-matches #"^\s*>\s?(.*)$" line)]
    body))

(defn- table-row? [line]
  (let [trimmed (str/trim line)]
    (and (str/starts-with? trimmed "|")
         (str/ends-with? trimmed "|")
         (> (count trimmed) 2))))

(defn- table-separator? [line]
  (and (table-row? line)
       (boolean (re-matches #"^\s*\|[\s:|-]+\|\s*$" line))))

(defn- split-table-cells [line]
  (let [trimmed (-> line str/trim (str/replace #"^\|" "") (str/replace #"\|$" ""))]
    (mapv (comp parse-inline str/trim) (str/split trimmed #"\|"))))

(defn- normalize-inline-headings
  "Splits mid-line '### Heading' or '---' markers emitted when a model omits newlines."
  [text]
  (-> (str (or text ""))
      (str/replace #"\r\n?" "\n")
      (str/replace #"([^\n#])(#{2,6}\s*[^\n#])" "$1\n\n$2")))

(defn- consume-code-block [lines i n {:keys [fence lang]}]
  (loop [j (inc i)
         code-lines []]
    (if (or (>= j n) (fence-close? (nth lines j) fence))
      {:next-i (min n (inc j))
       :block {:type :code-block
               :lang lang
               :code (str/join "\n" code-lines)}}
      (recur (inc j) (conj code-lines (nth lines j))))))

(defn- consume-table [lines i n]
  (let [headers (split-table-cells (nth lines i))]
    (loop [j (+ i 2)
           rows []]
      (if (and (< j n) (table-row? (nth lines j)) (not (table-separator? (nth lines j))))
        (recur (inc j) (conj rows (split-table-cells (nth lines j))))
        {:next-i j
         :block {:type :table :headers headers :rows rows}}))))

(defn- consume-blockquote [lines i n]
  (loop [j i
         quote-lines []]
    (if-let [q (and (< j n) (blockquote-line (nth lines j)))]
      (recur (inc j) (conj quote-lines q))
      {:next-i j
       :block {:type :blockquote
               :children (parse-inline (str/join "\n" quote-lines))}})))

(defn- consume-ul [lines i n]
  (loop [j i
         items []]
    (if-let [item (and (< j n) (ul-item (nth lines j)))]
      (recur (inc j) (conj items item))
      {:next-i j
       :block {:type :ul :items items}})))

(defn- consume-ol [lines i n]
  (loop [j i
         items []]
    (if-let [item (and (< j n) (ol-item (nth lines j)))]
      (recur (inc j) (conj items item))
      {:next-i j
       :block {:type :ol :items items}})))

(defn- block-starter? [lines j n]
  (let [line (nth lines j)]
    (or (str/blank? line)
        (some? (fence-open line))
        (hr-line? line)
        (some? (heading-line line))
        (some? (blockquote-line line))
        (some? (ul-item line))
        (some? (ol-item line))
        (and (< (inc j) n)
             (table-row? line)
             (table-separator? (nth lines (inc j)))))))

(defn- consume-paragraph [lines i n]
  (loop [j (inc i)
         para-lines [(nth lines i)]]
    (if (or (>= j n) (block-starter? lines j n))
      {:next-i j
       :block {:type :paragraph
               :children (parse-inline (str/join "\n" para-lines))}}
      (recur (inc j) (conj para-lines (nth lines j))))))

(defn parse-blocks
  "Parses a Markdown document string into a vector of block AST nodes:
  :heading, :code-block, :hr, :blockquote, :ul, :ol, :table, and :paragraph."
  [text]
  (let [cleaned (normalize-inline-headings text)
        lines (vec (str/split cleaned #"\n" -1))
        n (count lines)]
    (loop [i 0
           blocks []]
      (if (>= i n)
        blocks
        (let [line (nth lines i)]
          (cond
            (str/blank? line)
            (recur (inc i) blocks)

            (fence-open line)
            (let [{:keys [next-i block]} (consume-code-block lines i n (fence-open line))]
              (recur next-i (conj blocks block)))

            (hr-line? line)
            (recur (inc i) (conj blocks {:type :hr}))

            (heading-line line)
            (recur (inc i) (conj blocks (heading-line line)))

            (and (< (inc i) n)
                 (table-row? line)
                 (table-separator? (nth lines (inc i))))
            (let [{:keys [next-i block]} (consume-table lines i n)]
              (recur next-i (conj blocks block)))

            (some? (blockquote-line line))
            (let [{:keys [next-i block]} (consume-blockquote lines i n)]
              (recur next-i (conj blocks block)))

            (some? (ul-item line))
            (let [{:keys [next-i block]} (consume-ul lines i n)]
              (recur next-i (conj blocks block)))

            (some? (ol-item line))
            (let [{:keys [next-i block]} (consume-ol lines i n)]
              (recur next-i (conj blocks block)))

            :else
            (let [{:keys [next-i block]} (consume-paragraph lines i n)]
              (recur next-i (conj blocks block)))))))))
