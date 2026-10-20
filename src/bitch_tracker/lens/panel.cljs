(ns bitch-tracker.lens.panel
  "KnoxxLens drawer DOM. Builds the skeleton once, then re-renders header,
  session history, chips, and Markdown transcript with collapsible tool receipts
  from a plain view map. Untrusted text is only ever assigned through textContent.
  All I/O against document; no domain policy."
  (:require [bitch-tracker.domain.markdown :as md]
            [bitch-tracker.plugin.bd :as bd]
            [bitch-tracker.shared.util :as u]
            [clojure.string :as str]))

(def style-id
  "BetterDiscord style id for the drawer stylesheet."
  "knoxx-lens-styles")

(def css
  "Drawer stylesheet; colors come from Discord theme variables (and therefore UXX when installed)."
  "
.knoxx-lens-toggle{position:fixed;right:0;top:50%;transform:translateY(-50%);z-index:1001;padding:10px 6px;border:none;border-radius:8px 0 0 8px;background:var(--brand-500,var(--button-background,#5865f2));color:#fff;font-weight:700;cursor:pointer;writing-mode:vertical-rl;letter-spacing:.08em;user-select:none;-webkit-user-select:none}
.knoxx-lens{position:fixed;top:48px;right:0;bottom:0;width:440px;max-width:92vw;z-index:1000;display:none;flex-direction:column;background:var(--background-secondary,#2b2d31);color:var(--text-normal,#dbdee1);border-left:1px solid var(--background-modifier-accent,#3f4147);box-shadow:-8px 0 24px rgba(0,0,0,.25);font-size:14px;user-select:text !important;-webkit-user-select:text !important}
.knoxx-lens.open{display:flex}
.knoxx-lens header{display:flex;align-items:center;gap:6px;padding:10px 12px;border-bottom:1px solid var(--background-modifier-accent,#3f4147);user-select:none;-webkit-user-select:none}
.knoxx-lens header .title{flex:1;font-weight:700;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.knoxx-lens .badge{font-size:11px;padding:2px 6px;border-radius:999px;background:var(--background-tertiary,#1e1f22);color:var(--text-muted,#949ba4);user-select:none;-webkit-user-select:none}
.knoxx-lens .badge.ready{color:var(--text-positive,#23a55a)}
.knoxx-lens .badge.blocked{color:var(--text-danger,#f23f43)}
.knoxx-lens button.k-btn{border:none;border-radius:6px;padding:5px 9px;font-size:12px;background:var(--background-modifier-accent,#3f4147);color:var(--text-normal,#dbdee1);cursor:pointer;user-select:none;-webkit-user-select:none}
.knoxx-lens button.k-btn.primary,.knoxx-lens button.k-btn.active{background:var(--brand-500,var(--button-background,#5865f2));color:#fff}
.knoxx-lens button.k-btn:disabled{opacity:.5;cursor:default}
.knoxx-lens .sessions-drawer{display:none;flex-direction:column;border-bottom:1px solid var(--background-modifier-accent,#3f4147);background:var(--background-tertiary,#1e1f22);max-height:240px}
.knoxx-lens .sessions-drawer.open{display:flex}
.knoxx-lens .sessions-bar{display:flex;align-items:center;justify-content:space-between;gap:6px;padding:6px 12px;border-bottom:1px solid var(--background-modifier-accent,#3f4147);font-size:11px;color:var(--text-muted,#949ba4);user-select:none;-webkit-user-select:none}
.knoxx-lens .sessions-bar .bar-actions{display:flex;gap:6px}
.knoxx-lens .sessions-list{overflow-y:auto;display:flex;flex-direction:column;padding:4px 6px;gap:2px}
.knoxx-lens .session-item{display:flex;flex-direction:column;gap:2px;padding:6px 8px;border-radius:6px;cursor:pointer;border:1px solid transparent;user-select:none;-webkit-user-select:none}
.knoxx-lens .session-item:hover{background:var(--background-modifier-hover,#35373c)}
.knoxx-lens .session-item.active{background:var(--background-primary,#313338);border-color:var(--brand-500,#5865f2)}
.knoxx-lens .session-item .s-title{font-size:12px;font-weight:600;color:var(--text-normal,#dbdee1);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.knoxx-lens .session-item .s-meta{font-size:11px;color:var(--text-muted,#949ba4);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.knoxx-lens .chips{display:flex;flex-wrap:wrap;gap:6px;padding:8px 12px 0;user-select:none;-webkit-user-select:none}
.knoxx-lens .chip{display:inline-flex;align-items:center;gap:6px;max-width:100%;padding:3px 8px;border-radius:999px;border:1px solid var(--background-modifier-accent,#3f4147);background:var(--background-primary,#313338);font-size:12px;cursor:pointer;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.knoxx-lens .chip.off{opacity:.45;text-decoration:line-through}
.knoxx-lens .chip .x{color:var(--text-muted,#949ba4)}
.knoxx-lens .budget{padding:4px 12px 8px;font-size:11px;color:var(--text-muted,#949ba4);border-bottom:1px solid var(--background-modifier-accent,#3f4147);user-select:none;-webkit-user-select:none}
.knoxx-lens .budget.warn{color:var(--text-warning,#f0b232)}
.knoxx-lens .problems{padding:8px 12px;font-size:12px;color:var(--text-danger,#f23f43);white-space:pre-wrap;user-select:text !important;-webkit-user-select:text !important}
.knoxx-lens .transcript{flex:1;overflow-y:auto;padding:12px;display:flex;flex-direction:column;gap:12px;user-select:text !important;-webkit-user-select:text !important}
.knoxx-lens .turn{word-break:break-word;line-height:1.45;padding:8px 10px;border-radius:8px;background:var(--background-primary,#313338);user-select:text !important;-webkit-user-select:text !important}
.knoxx-lens .turn.user{background:var(--background-tertiary,#1e1f22)}
.knoxx-lens .turn *{user-select:text !important;-webkit-user-select:text !important}
.knoxx-lens .turn-header{display:flex;align-items:center;justify-content:space-between;margin-bottom:4px}
.knoxx-lens .turn .who{font-weight:700;font-size:12px;text-transform:uppercase;letter-spacing:.04em}
.knoxx-lens .turn.user .who{color:var(--text-link,#00a8fc)}
.knoxx-lens .turn.assistant .who{color:var(--text-positive,#23a55a)}
.knoxx-lens .turn .meta{display:block;font-size:11px;color:var(--text-muted,#949ba4);margin-top:6px;padding-top:4px;border-top:1px solid var(--background-modifier-accent,#3f4147)}
.knoxx-lens .turn.error .body{color:var(--text-danger,#f23f43)}
.knoxx-lens .md-body{display:flex;flex-direction:column;gap:8px}
.knoxx-lens .md-p{margin:0;white-space:pre-wrap}
.knoxx-lens .md-h1,.knoxx-lens .md-h2,.knoxx-lens .md-h3,.knoxx-lens .md-h4{margin:4px 0 0;font-weight:700;line-height:1.25;color:var(--header-primary,#f2f3f5)}
.knoxx-lens .md-h1{font-size:17px;border-bottom:1px solid var(--background-modifier-accent,#3f4147);padding-bottom:3px}
.knoxx-lens .md-h2{font-size:15px}
.knoxx-lens .md-h3{font-size:14px}
.knoxx-lens .md-h4{font-size:13px;color:var(--text-muted,#949ba4)}
.knoxx-lens .md-ul,.knoxx-lens .md-ol{margin:0;padding-left:20px;display:flex;flex-direction:column;gap:4px}
.knoxx-lens .md-li{line-height:1.4}
.knoxx-lens .md-quote{margin:0;padding:4px 10px;border-left:3px solid var(--brand-500,#5865f2);background:var(--background-secondary,#2b2d31);color:var(--text-muted,#949ba4);border-radius:0 4px 4px 0;white-space:pre-wrap}
.knoxx-lens .md-hr{border:none;border-top:1px solid var(--background-modifier-accent,#3f4147);margin:4px 0}
.knoxx-lens .md-code{font-family:var(--font-code,monospace);font-size:12.5px;padding:1px 5px;border-radius:4px;background:var(--background-tertiary,#1e1f22);color:var(--text-normal,#dbdee1)}
.knoxx-lens .md-code-wrap{border-radius:6px;overflow:hidden;border:1px solid var(--background-modifier-accent,#3f4147);background:var(--background-tertiary,#1e1f22)}
.knoxx-lens .md-code-bar{display:flex;align-items:center;justify-content:space-between;padding:4px 8px;font-size:11px;color:var(--text-muted,#949ba4);background:var(--background-secondary,#2b2d31);border-bottom:1px solid var(--background-modifier-accent,#3f4147)}
.knoxx-lens .md-pre{margin:0;padding:8px 10px;overflow-x:auto;font-family:var(--font-code,monospace);font-size:12px;line-height:1.4;white-space:pre}
.knoxx-lens .md-link{color:var(--text-link,#00a8fc);text-decoration:underline;cursor:pointer}
.knoxx-lens .md-table-wrap{overflow-x:auto}
.knoxx-lens .md-table{width:100%;border-collapse:collapse;font-size:12px}
.knoxx-lens .md-table th,.knoxx-lens .md-table td{border:1px solid var(--background-modifier-accent,#3f4147);padding:4px 8px;text-align:left}
.knoxx-lens .md-table th{background:var(--background-secondary,#2b2d31);font-weight:700}
.knoxx-lens .tool-receipts{display:flex;flex-direction:column;gap:5px;margin:4px 0 8px}
.knoxx-lens details.tool-receipt{border:1px solid var(--background-modifier-accent,#3f4147);border-radius:6px;background:var(--background-secondary,#2b2d31);font-size:12px;overflow:hidden}
.knoxx-lens details.tool-receipt summary{display:flex;align-items:center;gap:6px;padding:5px 8px;cursor:pointer;list-style:none;user-select:none;-webkit-user-select:none}
.knoxx-lens details.tool-receipt summary::-webkit-details-marker{display:none}
.knoxx-lens details.tool-receipt summary .tr-chevron{font-size:10px;color:var(--text-muted,#949ba4);transition:transform .15s ease}
.knoxx-lens details.tool-receipt[open] summary .tr-chevron{transform:rotate(90deg)}
.knoxx-lens details.tool-receipt .tr-status{font-size:11px;font-weight:700}
.knoxx-lens details.tool-receipt.running .tr-status{color:var(--text-warning,#f0b232)}
.knoxx-lens details.tool-receipt.done .tr-status{color:var(--text-positive,#23a55a)}
.knoxx-lens details.tool-receipt.error .tr-status{color:var(--text-danger,#f23f43)}
.knoxx-lens details.tool-receipt .tr-name{font-family:var(--font-code,monospace);font-weight:700;padding:1px 5px;border-radius:4px;background:var(--background-tertiary,#1e1f22)}
.knoxx-lens details.tool-receipt .tr-preview{flex:1;color:var(--text-muted,#949ba4);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:11px}
.knoxx-lens .tool-receipt-body{padding:6px 8px;border-top:1px solid var(--background-modifier-accent,#3f4147);background:var(--background-tertiary,#1e1f22);display:flex;flex-direction:column;gap:6px}
.knoxx-lens .tr-section-label{font-size:10px;font-weight:700;text-transform:uppercase;letter-spacing:.05em;color:var(--text-muted,#949ba4);margin-bottom:2px}
.knoxx-lens .tr-pre{margin:0;padding:6px;border-radius:4px;background:var(--background-secondary,#2b2d31);font-family:var(--font-code,monospace);font-size:11px;line-height:1.35;white-space:pre-wrap;word-break:break-word;max-height:220px;overflow-y:auto}
.knoxx-lens .empty{color:var(--text-muted,#949ba4);font-size:13px}
.knoxx-lens footer{display:flex;gap:8px;padding:10px 12px;border-top:1px solid var(--background-modifier-accent,#3f4147)}
.knoxx-lens textarea{flex:1;resize:none;min-height:56px;max-height:160px;padding:8px;border-radius:6px;border:1px solid var(--background-modifier-accent,#3f4147);background:var(--channeltextarea-background,var(--background-primary,#313338));color:var(--text-normal,#dbdee1);font:inherit;user-select:text !important;-webkit-user-select:text !important}
")

(defn- el
  "Creates an element with optional class and text."
  ([document tag] (el document tag nil nil))
  ([document tag class-name] (el document tag class-name nil))
  ([document tag class-name text]
   (let [node (.createElement document tag)]
     (when class-name (set! (.-className node) class-name))
     (when (some? text) (set! (.-textContent node) (str text)))
     node)))

(defn- button [document label class-name on-click]
  (doto (el document "button" (str "k-btn " class-name) label)
    (.addEventListener "click" (fn [e] (.preventDefault e) (on-click)))))

(defn- copy-text! [text]
  (when-let [clipboard (some-> (bd/global-get "navigator") .-clipboard)]
    (.writeText clipboard (str text))))

;; ─────────────────────────────────────────────────────────────
;; Safe Markdown DOM builder (zero innerHTML)
;; ─────────────────────────────────────────────────────────────

(declare append-inline-nodes!)

(defn- inline-node->dom [document {:keys [text children label href] node-type :type}]
  (case node-type
    :text (.createTextNode document (str text))
    :code (el document "code" "md-code" text)
    :bold (let [n (el document "strong")] (append-inline-nodes! document n children) n)
    :italic (let [n (el document "em")] (append-inline-nodes! document n children) n)
    :bold-italic (let [strong (el document "strong")
                       em (el document "em")]
                   (append-inline-nodes! document em children)
                   (.appendChild strong em)
                   strong)
    :strike (let [n (el document "del")] (append-inline-nodes! document n children) n)
    :link (doto (el document "a" "md-link" label)
            (.setAttribute "href" (str href))
            (.setAttribute "target" "_blank")
            (.setAttribute "rel" "noopener noreferrer"))
    (.createTextNode document (str text))))

(defn- append-inline-nodes! [document parent nodes]
  (doseq [n nodes]
    (.appendChild parent (inline-node->dom document n))))

(defn- code-block-node [document {:keys [lang code]}]
  (let [wrap (el document "div" "md-code-wrap")
        bar (el document "div" "md-code-bar")
        lang-el (el document "span" nil (if (str/blank? lang) "code" lang))
        copy-btn (button document "Copy" "" #(do (copy-text! code) (bd/toast! "Copied code" "success")))
        pre (el document "pre" "md-pre")
        code-el (el document "code" nil code)]
    (.append bar lang-el copy-btn)
    (.appendChild pre code-el)
    (.append wrap bar pre)
    wrap))

(defn- table-node [document {:keys [headers rows]}]
  (let [wrap (el document "div" "md-table-wrap")
        tbl (el document "table" "md-table")
        thead (el document "thead")
        tr-head (el document "tr")
        tbody (el document "tbody")]
    (doseq [cell headers]
      (let [th (el document "th")]
        (append-inline-nodes! document th cell)
        (.appendChild tr-head th)))
    (.appendChild thead tr-head)
    (doseq [row rows]
      (let [tr (el document "tr")]
        (doseq [cell row]
          (let [td (el document "td")]
            (append-inline-nodes! document td cell)
            (.appendChild tr td)))
        (.appendChild tbody tr)))
    (.append tbl thead tbody)
    (.appendChild wrap tbl)
    wrap))

(defn- block-node->dom [document {:keys [level children items] block-type :type :as block}]
  (case block-type
    :heading (let [lvl (min 4 (max 1 (or level 3)))
                   h (el document (str "h" lvl) (str "md-h" lvl))]
               (append-inline-nodes! document h children)
               h)
    :code-block (code-block-node document block)
    :hr (el document "hr" "md-hr")
    :blockquote (let [q (el document "blockquote" "md-quote")]
                  (append-inline-nodes! document q children)
                  q)
    :ul (let [ul (el document "ul" "md-ul")]
          (doseq [item items]
            (let [li (el document "li" "md-li")]
              (when (pos? (:indent item 0))
                (set! (.. li -style -marginLeft) (str (* 8 (min 4 (quot (:indent item 0) 2))) "px")))
              (append-inline-nodes! document li (:children item))
              (.appendChild ul li)))
          ul)
    :ol (let [ol (el document "ol" "md-ol")]
          (when-let [start (:index (first items))]
            (when (> start 1) (.setAttribute ol "start" (str start))))
          (doseq [item items]
            (let [li (el document "li" "md-li")]
              (append-inline-nodes! document li (:children item))
              (.appendChild ol li)))
          ol)
    :table (table-node document block)
    :paragraph (let [p (el document "p" "md-p")]
                 (append-inline-nodes! document p children)
                 p)
    (el document "p" "md-p" (str (:text block)))))

(defn render-markdown!
  "Parses text as Markdown and appends safe DOM nodes to container."
  [document container text]
  (let [blocks (md/parse-blocks text)]
    (if (seq blocks)
      (doseq [b blocks]
        (.appendChild container (block-node->dom document b)))
      (.appendChild container (el document "p" "md-p" (str text))))))

;; ─────────────────────────────────────────────────────────────
;; Collapsible Tool Receipts
;; ─────────────────────────────────────────────────────────────

(defn- one-line-preview [s max-len]
  (let [clean (-> (str (or s "")) (str/replace #"\s+" " ") str/trim)]
    (if (> (count clean) max-len)
      (str (subs clean 0 (dec max-len)) "…")
      clean)))

(defn- receipt-section [document label content]
  (let [wrap (el document "div")
        hdr (el document "div" "tr-section-label" label)
        pre (el document "pre" "tr-pre" content)]
    (.append wrap hdr pre)
    wrap))

(defn- normalize-turn-receipts [{:keys [tool-receipts events]}]
  (if (seq tool-receipts)
    tool-receipts
    (->> events
         (keep-indexed (fn [idx {:keys [tool toolCallId preview isError] event-type :type}]
                         (when (and tool (not= "abort_requested" event-type))
                           {:id (or toolCallId (str tool "#" idx))
                            :tool tool
                            :status (cond isError :error (= "tool_start" event-type) :running :else :done)
                            :input-preview (when (= "tool_start" event-type) preview)
                            :updates []
                            :output-preview (when (= "tool_end" event-type) preview)
                            :is-error (boolean isError)})))
         vec)))

(defn- tool-receipt-node [document open-receipts request-id {:keys [id tool status input-preview updates output-preview]}]
  (let [receipt-key (str (or request-id "turn") ":" (or id tool))
        status-kw (or status :done)
        status-class (name status-kw)
        status-icon (case status-kw :running "⏳" :error "✗" "✓")
        summary-hint (one-line-preview (or input-preview (first updates) output-preview "") 64)
        details (el document "details" (str "tool-receipt " status-class))
        summary (el document "summary")
        chevron (el document "span" "tr-chevron" "▶")
        status-el (el document "span" "tr-status" status-icon)
        name-el (el document "span" "tr-name" (str tool))
        preview-el (el document "span" "tr-preview" summary-hint)
        body (el document "div" "tool-receipt-body")]
    (when (and open-receipts (.has open-receipts receipt-key))
      (set! (.-open details) true))
    (.addEventListener details "toggle"
                       (fn [_]
                         (when open-receipts
                           (if (.-open details)
                             (.add open-receipts receipt-key)
                             (.delete open-receipts receipt-key)))))
    (.append summary chevron status-el name-el preview-el)
    (when (seq (str input-preview))
      (.appendChild body (receipt-section document "Input" input-preview)))
    (when (seq updates)
      (.appendChild body (receipt-section document "Updates" (str/join "\n" updates))))
    (when (seq (str output-preview))
      (.appendChild body (receipt-section document "Result" output-preview)))
    (when (and (str/blank? (str input-preview))
               (empty? updates)
               (str/blank? (str output-preview)))
      (.appendChild body (el document "div" "tr-section-label"
                             (if (= :running status-kw) "Running tool…" "Completed (no preview payload)"))))
    (.append details summary body)
    details))

;; ─────────────────────────────────────────────────────────────
;; Drawer mount & render
;; ─────────────────────────────────────────────────────────────

(defn mount!
  "Builds the drawer and toggle tab, appends them to document.body, and returns refs."
  [{:keys [on-toggle on-close on-new on-history-toggle on-history-filter on-history-refresh on-send on-stop]}]
  (let [document (bd/global-get "document")
        add-style (u/jget (bd/bd-api) "DOM" "addStyle")
        root (el document "div" "knoxx-lens")
        toggle (button document "KNOXX" "knoxx-lens-toggle" on-toggle)
        header (el document "header")
        title (el document "span" "title" "Knoxx Lens")
        status (el document "span" "badge" "connecting")
        history-btn (button document "History" "" #(when on-history-toggle (on-history-toggle)))
        new-btn (button document "New" "" on-new)
        close-btn (button document "×" "" on-close)
        sessions-drawer (el document "div" "sessions-drawer")
        sessions-bar (el document "div" "sessions-bar")
        sessions-label (el document "span" nil "Past Sessions")
        bar-actions (el document "div" "bar-actions")
        filter-btn (button document "All" "" #(when on-history-filter (on-history-filter)))
        refresh-btn (button document "↻" "" #(when on-history-refresh (on-history-refresh)))
        sessions-list (el document "div" "sessions-list")
        chips (el document "div" "chips")
        budget (el document "div" "budget")
        problems (el document "div" "problems")
        transcript (el document "div" "transcript")
        footer (el document "footer")
        input (doto (el document "textarea")
                (aset "placeholder" "Ask about this channel…  (Enter to send, Shift+Enter for newline, @last:N sets tail)"))
        send-btn (button document "Send" "primary" #(on-send (.-value input)))
        stop-btn (button document "Stop" "" on-stop)]
    (set! (.-className toggle) "knoxx-lens-toggle")
    (when add-style (.call add-style (u/jget (bd/bd-api) "DOM") style-id css))
    (.addEventListener input "keydown"
                       (fn [^js e]
                         (when (and (= "Enter" (.-key e)) (not (.-shiftKey e)))
                           (.preventDefault e)
                           (on-send (.-value input)))))
    (.append bar-actions filter-btn refresh-btn)
    (.append sessions-bar sessions-label bar-actions)
    (.append sessions-drawer sessions-bar sessions-list)
    (.append header title status history-btn new-btn close-btn)
    (.append footer input send-btn stop-btn)
    (.append root header sessions-drawer chips budget problems transcript footer)
    (.append (.-body document) toggle root)
    {:document document :root root :toggle toggle :title title :status status
     :history-btn history-btn :sessions-drawer sessions-drawer
     :sessions-label sessions-label :filter-btn filter-btn :sessions-list sessions-list
     :chips chips :budget budget :problems problems :transcript transcript
     :input input :send send-btn :stop stop-btn
     :open-receipts (js/Set.)}))

(defn unmount!
  "Removes the drawer, toggle tab, and stylesheet."
  [{:keys [root toggle]}]
  (when root (.remove root))
  (when toggle (.remove toggle))
  (when-let [remove-style (u/jget (bd/bd-api) "DOM" "removeStyle")]
    (.call remove-style (u/jget (bd/bd-api) "DOM") style-id)))

(defn- render-chips! [{:keys [document chips]} chip-views]
  (set! (.-textContent chips) "")
  (doseq [{:keys [label on-click removable? off?]} chip-views]
    (let [chip (el document "span" (str "chip" (when off? " off")))]
      (.append chip (el document "span" nil label))
      (when removable? (.append chip (el document "span" "x" "×")))
      (.addEventListener chip "click" (fn [_] (on-click)))
      (.appendChild chips chip))))

(defn- format-short-ts [iso-str]
  (if (str/blank? (str iso-str))
    ""
    (let [ms (js/Date.parse (str iso-str))]
      (if (js/isNaN ms)
        ""
        (let [d (js/Date. ms)
              pad #(if (< % 10) (str "0" %) (str %))]
          (str (inc (.getMonth d)) "/" (.getDate d) " "
               (pad (.getHours d)) ":" (pad (.getMinutes d))))))))

(defn- render-sessions!
  [{:keys [document history-btn sessions-drawer sessions-label filter-btn sessions-list]}
   {:keys [sessions-open? channel-only-sessions? sessions active-conversation-id on-select-session]}]
  (.toggle (.-classList sessions-drawer) "open" (boolean sessions-open?))
  (.toggle (.-classList history-btn) "active" (boolean sessions-open?))
  (set! (.-textContent history-btn)
        (if (pos? (count sessions))
          (str "History (" (count sessions) ")")
          "History"))
  (set! (.-textContent filter-btn) (if channel-only-sessions? "This Channel" "All Sessions"))
  (set! (.-textContent sessions-label)
        (if channel-only-sessions? "Channel Sessions" "All Knoxx Sessions"))
  (set! (.-textContent sessions-list) "")
  (if (seq sessions)
    (doseq [{:keys [conversation-id title channel-name turn-count updated-at] :as item} sessions]
      (let [active? (= (str conversation-id) (str active-conversation-id))
            row (el document "div" (str "session-item" (when active? " active")))
            t-el (el document "div" "s-title" (str (when active? "● ") (or title "Untitled session")))
            meta-parts (cond-> []
                         (seq channel-name) (conj (str "#" channel-name))
                         (pos? (or turn-count 0)) (conj (str turn-count " items"))
                         (seq (format-short-ts updated-at)) (conj (format-short-ts updated-at))
                         :always (conj (subs (str conversation-id) 0 (min 8 (count (str conversation-id))))))
            m-el (el document "div" "s-meta" (str/join " · " meta-parts))]
        (.append row t-el m-el)
        (.addEventListener row "click" (fn [_] (when on-select-session (on-select-session item))))
        (.appendChild sessions-list row)))
    (.appendChild sessions-list (el document "div" "empty" "No past sessions found."))))

(defn- turn-node [document open-receipts {:keys [role request-id text status model context error] :as turn}]
  (let [assistant? (= :assistant role)
        node (el document "div" (str "turn " (name role) (when (= :error status) " error")))
        hdr (el document "div" "turn-header")
        who (el document "span" "who" (if assistant? "knoxx" "you"))
        receipts (when assistant? (normalize-turn-receipts turn))
        body-el (el document "div" "body md-body")
        body-text (cond
                    (= :error status) (str (when (seq text) (str text "\n\n")) "⚠ " error)
                    (and assistant? (str/blank? text) (#{:pending :streaming} status)) "…"
                    :else (str text))
        meta-parts (cond-> []
                     (seq context) (conj (str "context: " (str/join ", " context)))
                     (seq receipts) (conj (str (count receipts) " tool" (when (> (count receipts) 1) "s")))
                     model (conj (str "model: " model)))]
    (.appendChild hdr who)
    (.appendChild node hdr)
    (when (seq receipts)
      (let [rc-wrap (el document "div" "tool-receipts")]
        (doseq [r receipts]
          (.appendChild rc-wrap (tool-receipt-node document open-receipts request-id r)))
        (.appendChild node rc-wrap)))
    (render-markdown! document body-el body-text)
    (.appendChild node body-el)
    (when (seq meta-parts)
      (.appendChild node (el document "span" "meta" (str/join " · " meta-parts))))
    node))

(defn render!
  "Re-renders header, session history, chips, budget, problems, and transcript from view."
  [{:keys [document root toggle title status budget problems transcript send stop open-receipts] :as refs}
   {:keys [open? channel connected? lens-config chips budget-text over-budget? turns running?] :as view-map}]
  (.toggle (.-classList root) "open" (boolean open?))
  (set! (.. toggle -style -display) (if open? "none" "block"))
  (set! (.-textContent title) (str "Knoxx Lens · " (if channel (str "#" (:name channel)) "no channel")))
  (let [ready? (and connected? (:ready lens-config))]
    (set! (.-textContent status) (cond (not connected?) "bot offline"
                                       ready? (str (:model lens-config))
                                       :else "blocked"))
    (set! (.-className status) (str "badge " (if ready? "ready" "blocked")))
    (set! (.-disabled send) (or running? (not ready?) (nil? channel)))
    (set! (.-disabled stop) (not running?)))
  (render-sessions! refs view-map)
  (set! (.-textContent problems) (str/join "\n" (:problems lens-config)))
  (set! (.. problems -style -display) (if (seq (:problems lens-config)) "block" "none"))
  (render-chips! refs chips)
  (set! (.-textContent budget) budget-text)
  (.toggle (.-classList budget) "warn" (boolean over-budget?))
  (let [near-bottom? (< (- (.-scrollHeight transcript) (.-scrollTop transcript) (.-clientHeight transcript)) 40)]
    (set! (.-textContent transcript) "")
    (if (seq turns)
      (doseq [turn turns] (.appendChild transcript (turn-node document open-receipts turn)))
      (.appendChild transcript (el document "div" "empty" "Ask about the open channel. Right-click any message → \"Add to Knoxx context\" to pin it, or click History to browse past sessions.")))
    (when near-bottom? (set! (.-scrollTop transcript) (.-scrollHeight transcript)))))

(defn clear-input!
  "Clears and refocuses the composer."
  [{:keys [input]}]
  (set! (.-value input) "")
  (.focus input))
