(ns bitch-tracker.lens.panel
  "KnoxxLens drawer DOM. Builds the skeleton once, then re-renders header,
  chips, and transcript from a plain view map. Untrusted text is only ever
  assigned through textContent. All I/O against document; no domain policy."
  (:require [bitch-tracker.plugin.bd :as bd]
            [bitch-tracker.shared.util :as u]
            [clojure.string :as str]))

(def style-id
  "BetterDiscord style id for the drawer stylesheet."
  "knoxx-lens-styles")

(def css
  "Drawer stylesheet; colors come from Discord theme variables (and therefore UXX when installed)."
  "
.knoxx-lens-toggle{position:fixed;right:0;top:50%;transform:translateY(-50%);z-index:1001;padding:10px 6px;border:none;border-radius:8px 0 0 8px;background:var(--brand-500,var(--button-background,#5865f2));color:#fff;font-weight:700;cursor:pointer;writing-mode:vertical-rl;letter-spacing:.08em;user-select:none;-webkit-user-select:none}
.knoxx-lens{position:fixed;top:48px;right:0;bottom:0;width:400px;max-width:90vw;z-index:1000;display:none;flex-direction:column;background:var(--background-secondary,#2b2d31);color:var(--text-normal,#dbdee1);border-left:1px solid var(--background-modifier-accent,#3f4147);box-shadow:-8px 0 24px rgba(0,0,0,.25);font-size:14px;user-select:text !important;-webkit-user-select:text !important}
.knoxx-lens.open{display:flex}
.knoxx-lens header{display:flex;align-items:center;gap:8px;padding:10px 12px;border-bottom:1px solid var(--background-modifier-accent,#3f4147);user-select:none;-webkit-user-select:none}
.knoxx-lens header .title{flex:1;font-weight:700;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.knoxx-lens .badge{font-size:11px;padding:2px 6px;border-radius:999px;background:var(--background-tertiary,#1e1f22);color:var(--text-muted,#949ba4);user-select:none;-webkit-user-select:none}
.knoxx-lens .badge.ready{color:var(--text-positive,#23a55a)}
.knoxx-lens .badge.blocked{color:var(--text-danger,#f23f43)}
.knoxx-lens button.k-btn{border:none;border-radius:6px;padding:6px 10px;background:var(--background-modifier-accent,#3f4147);color:var(--text-normal,#dbdee1);cursor:pointer;user-select:none;-webkit-user-select:none}
.knoxx-lens button.k-btn.primary{background:var(--brand-500,var(--button-background,#5865f2));color:#fff}
.knoxx-lens button.k-btn:disabled{opacity:.5;cursor:default}
.knoxx-lens .chips{display:flex;flex-wrap:wrap;gap:6px;padding:8px 12px 0;user-select:none;-webkit-user-select:none}
.knoxx-lens .chip{display:inline-flex;align-items:center;gap:6px;max-width:100%;padding:3px 8px;border-radius:999px;border:1px solid var(--background-modifier-accent,#3f4147);background:var(--background-primary,#313338);font-size:12px;cursor:pointer;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.knoxx-lens .chip.off{opacity:.45;text-decoration:line-through}
.knoxx-lens .chip .x{color:var(--text-muted,#949ba4)}
.knoxx-lens .budget{padding:4px 12px 8px;font-size:11px;color:var(--text-muted,#949ba4);border-bottom:1px solid var(--background-modifier-accent,#3f4147);user-select:none;-webkit-user-select:none}
.knoxx-lens .budget.warn{color:var(--text-warning,#f0b232)}
.knoxx-lens .problems{padding:8px 12px;font-size:12px;color:var(--text-danger,#f23f43);white-space:pre-wrap;user-select:text !important;-webkit-user-select:text !important}
.knoxx-lens .transcript{flex:1;overflow-y:auto;padding:12px;display:flex;flex-direction:column;gap:10px;user-select:text !important;-webkit-user-select:text !important}
.knoxx-lens .turn{white-space:pre-wrap;word-break:break-word;line-height:1.4;user-select:text !important;-webkit-user-select:text !important}
.knoxx-lens .turn *{user-select:text !important;-webkit-user-select:text !important}
.knoxx-lens .turn .who{font-weight:700;margin-right:6px}
.knoxx-lens .turn.user .who{color:var(--text-link,#00a8fc)}
.knoxx-lens .turn.assistant .who{color:var(--text-positive,#23a55a)}
.knoxx-lens .turn .meta{display:block;font-size:11px;color:var(--text-muted,#949ba4);margin-top:2px}
.knoxx-lens .turn.error .body{color:var(--text-danger,#f23f43)}
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

(defn mount!
  "Builds the drawer and toggle tab, appends them to document.body, and returns refs."
  [{:keys [on-toggle on-close on-new on-send on-stop]}]
  (let [document (bd/global-get "document")
        add-style (u/jget (bd/bd-api) "DOM" "addStyle")
        root (el document "div" "knoxx-lens")
        toggle (button document "KNOXX" "knoxx-lens-toggle" on-toggle)
        header (el document "header")
        title (el document "span" "title" "Knoxx Lens")
        status (el document "span" "badge" "connecting")
        new-btn (button document "New" "" on-new)
        close-btn (button document "×" "" on-close)
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
    (.append header title status new-btn close-btn)
    (.append footer input send-btn stop-btn)
    (.append root header chips budget problems transcript footer)
    (.append (.-body document) toggle root)
    {:document document :root root :toggle toggle :title title :status status
     :chips chips :budget budget :problems problems :transcript transcript
     :input input :send send-btn :stop stop-btn}))

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

(defn- turn-node [document {:keys [role text status events model context error]}]
  (let [assistant? (= :assistant role)
        node (el document "div" (str "turn " (name role) (when (= :error status) " error")))
        body (cond
               (= :error status) (str (when (seq text) (str text "\n\n")) "⚠ " error)
               (and assistant? (str/blank? text) (#{:pending :streaming} status)) "…"
               :else text)
        meta-parts (cond-> []
                     (seq context) (conj (str "context: " (str/join ", " context)))
                     (seq events) (conj (str "tools: " (str/join ", " (keep :tool events))))
                     model (conj (str "model: " model)))]
    (.append node (el document "span" "who" (if assistant? "knoxx" "you")) (el document "span" "body" body))
    (when (seq meta-parts) (.append node (el document "span" "meta" (str/join " · " meta-parts))))
    node))

(defn render!
  "Re-renders header, chips, budget, problems, and transcript from view."
  [{:keys [document root toggle title status budget problems transcript send stop] :as refs}
   {:keys [open? channel connected? lens-config chips budget-text over-budget? turns running?]}]
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
  (set! (.-textContent problems) (str/join "\n" (:problems lens-config)))
  (set! (.. problems -style -display) (if (seq (:problems lens-config)) "block" "none"))
  (render-chips! refs chips)
  (set! (.-textContent budget) budget-text)
  (.toggle (.-classList budget) "warn" (boolean over-budget?))
  (let [near-bottom? (< (- (.-scrollHeight transcript) (.-scrollTop transcript) (.-clientHeight transcript)) 40)]
    (set! (.-textContent transcript) "")
    (if (seq turns)
      (doseq [turn turns] (.appendChild transcript (turn-node document turn)))
      (.appendChild transcript (el document "div" "empty" "Ask about the open channel. Right-click any message → \"Add to Knoxx context\" to pin it.")))
    (when near-bottom? (set! (.-scrollTop transcript) (.-scrollHeight transcript)))))

(defn clear-input!
  "Clears and refocuses the composer."
  [{:keys [input]}]
  (set! (.-value input) "")
  (.focus input))
