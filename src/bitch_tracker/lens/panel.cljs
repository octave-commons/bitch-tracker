(ns bitch-tracker.lens.panel
  "KnoxxLens drawer DOM. Builds the skeleton once, then re-renders header,
  mode tabs (Chat, Pulse, Graph), session history, chips, Markdown transcript
  with collapsible tool receipts, live Channel Pulse digest, and interactive
  SVG + node/edge Graph Memory explorer from a plain view map.
  Untrusted text is only ever assigned through textContent.
  All I/O against document; no domain policy."
  (:require [bitch-tracker.domain.markdown :as md]
            [bitch-tracker.lens.webgl-view :as webgl-view]
            [bitch-tracker.plugin.bd :as bd]
            [bitch-tracker.shared.util :as u]
            [clojure.string :as str]))

(def style-id
  "BetterDiscord style id for the drawer stylesheet."
  "knoxx-lens-styles")

(def svg-ns
  "SVG XML namespace for interactive graph rendering."
  "http://www.w3.org/2000/svg")

(def css
  "Drawer stylesheet; colors come from Discord theme variables (and therefore UXX when installed)."
  "
.knoxx-lens-toggle{position:fixed;right:0;top:50%;transform:translateY(-50%);z-index:1001;padding:10px 6px;border:none;border-radius:8px 0 0 8px;background:var(--brand-500,var(--button-background,#5865f2));color:#fff;font-weight:700;cursor:pointer;writing-mode:vertical-rl;letter-spacing:.08em;user-select:none;-webkit-user-select:none}
.knoxx-lens{position:fixed;top:48px;right:0;bottom:0;width:460px;max-width:92vw;z-index:1000;display:none;flex-direction:column;background:var(--background-secondary,#2b2d31);color:var(--text-normal,#dbdee1);border-left:1px solid var(--background-modifier-accent,#3f4147);box-shadow:-8px 0 24px rgba(0,0,0,.28);font-size:14px;user-select:text !important;-webkit-user-select:text !important}
.knoxx-lens.open{display:flex}
.knoxx-lens header{display:flex;align-items:center;gap:6px;padding:10px 12px;border-bottom:1px solid var(--background-modifier-accent,#3f4147);user-select:none;-webkit-user-select:none}
.knoxx-lens header .title{flex:1;font-weight:700;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.knoxx-lens .badge{font-size:11px;padding:2px 6px;border-radius:999px;background:var(--background-tertiary,#1e1f22);color:var(--text-muted,#949ba4);user-select:none;-webkit-user-select:none}
.knoxx-lens .badge.ready{color:var(--text-positive,#23a55a)}
.knoxx-lens .badge.blocked{color:var(--text-danger,#f23f43)}
.knoxx-lens button.k-btn{border:none;border-radius:6px;padding:5px 9px;font-size:12px;background:var(--background-modifier-accent,#3f4147);color:var(--text-normal,#dbdee1);cursor:pointer;user-select:none;-webkit-user-select:none;display:inline-flex;align-items:center;gap:4px}
.knoxx-lens button.k-btn:hover:not(:disabled){filter:brightness(1.1)}
.knoxx-lens button.k-btn.primary,.knoxx-lens button.k-btn.active{background:var(--brand-500,var(--button-background,#5865f2));color:#fff}
.knoxx-lens button.k-btn.subtle{background:var(--background-tertiary,#1e1f22);font-size:11px;padding:3px 7px}
.knoxx-lens button.k-btn.pulse-on{background:rgba(35,165,90,.2);color:var(--text-positive,#23a55a);border:1px solid rgba(35,165,90,.4)}
.knoxx-lens button.k-btn:disabled{opacity:.5;cursor:default}
.knoxx-lens .mode-tabs{display:flex;align-items:center;gap:4px;padding:6px 12px;background:var(--background-tertiary,#1e1f22);border-bottom:1px solid var(--background-modifier-accent,#3f4147);user-select:none;-webkit-user-select:none}
.knoxx-lens .mode-tab{flex:1;justify-content:center;padding:6px 8px;border-radius:6px;font-weight:600;font-size:12px;background:transparent;color:var(--text-muted,#949ba4);border:1px solid transparent;cursor:pointer;display:inline-flex;align-items:center;gap:6px}
.knoxx-lens .mode-tab:hover{color:var(--text-normal,#dbdee1);background:var(--background-modifier-hover,#35373c)}
.knoxx-lens .mode-tab.active{background:var(--background-secondary,#2b2d31);color:var(--text-normal,#dbdee1);border-color:var(--brand-500,#5865f2)}
.knoxx-lens .mode-tab .tab-pill{font-size:10px;padding:1px 5px;border-radius:999px;background:var(--background-primary,#313338);color:var(--text-muted,#949ba4)}
.knoxx-lens .mode-tab.active .tab-pill{background:var(--brand-500,#5865f2);color:#fff}
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
.knoxx-lens .chip{display:inline-flex;align-items:center;gap:5px;max-width:100%;padding:3px 8px;border-radius:999px;border:1px solid var(--background-modifier-accent,#3f4147);background:var(--background-primary,#313338);font-size:11.5px;cursor:pointer;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.knoxx-lens .chip:hover{border-color:var(--brand-500,#5865f2)}
.knoxx-lens .chip.off{opacity:.45;text-decoration:line-through}
.knoxx-lens .chip .x{color:var(--text-muted,#949ba4)}
.knoxx-lens .budget{padding:4px 12px 8px;font-size:11px;color:var(--text-muted,#949ba4);border-bottom:1px solid var(--background-modifier-accent,#3f4147);user-select:none;-webkit-user-select:none}
.knoxx-lens .budget.warn{color:var(--text-warning,#f0b232)}
.knoxx-lens .problems{padding:8px 12px;font-size:12px;color:var(--text-danger,#f23f43);white-space:pre-wrap;user-select:text !important;-webkit-user-select:text !important}
.knoxx-lens .transcript,.knoxx-lens .pulse-pane,.knoxx-lens .graph-pane,.knoxx-lens .connections-pane,.knoxx-lens .extract-pane,.knoxx-lens .windows-pane{flex:1;overflow-y:auto;padding:12px;display:flex;flex-direction:column;gap:12px;user-select:text !important;-webkit-user-select:text !important}
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
.knoxx-lens .empty{color:var(--text-muted,#949ba4);font-size:13px;line-height:1.45}
.knoxx-lens .quick-starters{display:flex;flex-wrap:wrap;gap:6px;margin-top:8px}
.knoxx-lens .pulse-toolbar,.knoxx-lens .graph-toolbar{display:flex;align-items:center;justify-content:space-between;gap:6px;flex-wrap:wrap;padding:8px 10px;border-radius:8px;background:var(--background-tertiary,#1e1f22);border:1px solid var(--background-modifier-accent,#3f4147)}
.knoxx-lens .intel-card{display:flex;flex-direction:column;gap:6px;padding:8px 10px;border-radius:8px;background:var(--background-primary,#313338);border:1px solid var(--background-modifier-accent,#3f4147);font-size:12px}
.knoxx-lens .intel-row{display:flex;align-items:center;flex-wrap:wrap;gap:5px}
.knoxx-lens .intel-label{font-size:10px;font-weight:700;text-transform:uppercase;letter-spacing:.05em;color:var(--text-muted,#949ba4);margin-right:4px}
.knoxx-lens .graph-search-row{display:flex;gap:6px;width:100%}
.knoxx-lens .graph-input{flex:1;padding:6px 9px;border-radius:6px;border:1px solid var(--background-modifier-accent,#3f4147);background:var(--background-primary,#313338);color:var(--text-normal,#dbdee1);font-size:12.5px}
.knoxx-lens .graph-canvas-wrap{position:relative;border-radius:8px;background:var(--background-tertiary,#1e1f22);border:1px solid var(--background-modifier-accent,#3f4147);overflow:hidden;min-height:210px;display:flex;align-items:center;justify-content:center}
.knoxx-lens .graph-svg{width:100%;height:210px;display:block}
.knoxx-lens .graph-legend{position:absolute;bottom:6px;left:8px;display:flex;gap:8px;font-size:10px;color:var(--text-muted,#949ba4);background:rgba(30,31,34,.82);padding:2px 6px;border-radius:4px;pointer-events:none}
.knoxx-lens .graph-node-card{display:flex;flex-direction:column;gap:5px;padding:8px 10px;border-radius:8px;background:var(--background-primary,#313338);border:1px solid var(--background-modifier-accent,#3f4147);font-size:12px}
.knoxx-lens .graph-node-card.selected{border-color:var(--brand-500,#5865f2);box-shadow:0 0 0 1px var(--brand-500,#5865f2)}
.knoxx-lens .gn-header{display:flex;align-items:center;justify-content:space-between;gap:6px}
.knoxx-lens .gn-title{font-weight:700;color:var(--header-primary,#f2f3f5);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex:1}
.knoxx-lens .gn-kind{font-size:10px;font-weight:700;padding:1px 6px;border-radius:999px;background:var(--background-tertiary,#1e1f22);color:var(--text-muted,#949ba4)}
.knoxx-lens .gn-snippet{font-size:11.5px;color:var(--text-normal,#dbdee1);line-height:1.35;white-space:pre-wrap;word-break:break-word}
.knoxx-lens .gn-edges{display:flex;flex-wrap:wrap;gap:4px;margin-top:2px}
.knoxx-lens .gn-edge{font-size:10.5px;padding:2px 6px;border-radius:4px;background:var(--background-tertiary,#1e1f22);color:var(--text-muted,#949ba4);cursor:pointer;border:1px solid transparent}
.knoxx-lens .gn-edge:hover{border-color:var(--brand-500,#5865f2);color:var(--text-normal,#dbdee1)}
.knoxx-lens .gn-actions{display:flex;gap:6px;margin-top:2px}
.knoxx-lens .actor-badge{font-size:10px;padding:2px 6px;border-radius:4px;background:rgba(88,101,242,.18);color:#99a2ff;border:1px solid rgba(88,101,242,.35);font-family:var(--font-code,monospace)}
.knoxx-lens .draft-card{display:flex;flex-direction:column;gap:6px;margin-top:8px;padding:8px 10px;border-radius:8px;background:var(--background-secondary,#2b2d31);border:1px solid var(--brand-500,#5865f2)}
.knoxx-lens .draft-header{display:flex;align-items:center;justify-content:space-between;gap:6px;font-size:11px;font-weight:700;color:var(--header-primary,#f2f3f5)}
.knoxx-lens .draft-textarea{width:100%;box-sizing:border-box;min-height:60px;max-height:150px;resize:vertical;padding:6px 8px;border-radius:6px;border:1px solid var(--background-modifier-accent,#3f4147);background:var(--background-tertiary,#1e1f22);color:var(--text-normal,#dbdee1);font:inherit;font-size:12.5px}
.knoxx-lens .draft-actions{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:6px}
.knoxx-lens button.sugg-pill{border:1px solid var(--background-modifier-accent,#3f4147);border-radius:999px;padding:4px 9px;font-size:11.5px;background:var(--background-secondary,#2b2d31);color:var(--text-normal,#dbdee1);cursor:pointer;text-align:left;line-height:1.3;max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.knoxx-lens button.sugg-pill:hover{border-color:var(--brand-500,#5865f2);background:var(--background-modifier-hover,#35373c);color:#fff}
.knoxx-lens .suggestions-tray{display:flex;flex-direction:column;gap:4px;padding:6px 12px;border-top:1px solid var(--background-modifier-accent,#3f4147);background:var(--background-tertiary,#1e1f22)}
.knoxx-lens .suggestions-tray-hdr{display:flex;align-items:center;justify-content:space-between;font-size:10px;font-weight:700;text-transform:uppercase;letter-spacing:.05em;color:var(--text-muted,#949ba4)}
.knoxx-lens .suggestions-tray-list{display:flex;flex-wrap:wrap;gap:5px}
.knoxx-lens footer{display:flex;gap:8px;padding:10px 12px;border-top:1px solid var(--background-modifier-accent,#3f4147)}
.knoxx-lens textarea{flex:1;resize:none;min-height:56px;max-height:160px;padding:8px;border-radius:6px;border:1px solid var(--background-modifier-accent,#3f4147);background:var(--channeltextarea-background,var(--background-primary,#313338));color:var(--text-normal,#dbdee1);font:inherit;user-select:text !important;-webkit-user-select:text !important}
.knoxx-lens .telemetry-bar{display:flex;align-items:center;flex-wrap:wrap;gap:6px;font-size:10.5px;color:var(--text-muted,#949ba4);padding:4px 12px;background:var(--background-tertiary,#1e1f22);border-bottom:1px solid var(--background-modifier-accent,#3f4147);font-family:var(--font-code,monospace)}
.knoxx-lens .telemetry-phase{font-weight:700;padding:2px 6px;border-radius:4px}
.knoxx-lens .telemetry-phase.waiting{background:rgba(240,178,50,.18);color:var(--text-warning,#f0b232)}
.knoxx-lens .telemetry-phase.thinking{background:rgba(88,101,242,.2);color:#99a2ff}
.knoxx-lens .telemetry-phase.tool{background:rgba(235,69,158,.2);color:#eb459e}
.knoxx-lens .telemetry-phase.streaming{background:rgba(35,165,90,.2);color:var(--text-positive,#23a55a)}
.knoxx-lens .telemetry-phase.idle{background:var(--background-secondary,#2b2d31);color:var(--text-muted,#949ba4)}
.knoxx-lens .conn-canvas-wrap{position:relative;width:100%;height:320px;border-radius:8px;background:#050b14;border:1px solid var(--background-modifier-accent,#3f4147);overflow:hidden}
.knoxx-lens .knoxx-conn-canvas{width:100%;height:100%;display:block}
.knoxx-lens .extract-preview{padding:8px 10px;border-radius:6px;background:var(--background-primary,#313338);border:1px solid var(--background-modifier-accent,#3f4147);font-family:var(--font-code,monospace);font-size:11.5px;line-height:1.4;white-space:pre-wrap;word-break:break-word;max-height:180px;overflow-y:auto}
.knoxx-lens .extract-textarea{width:100%;box-sizing:border-box;min-height:70px;max-height:160px;resize:vertical;padding:6px 8px;border-radius:6px;border:1px solid var(--background-modifier-accent,#3f4147);background:var(--background-tertiary,#1e1f22);color:var(--text-normal,#dbdee1);font:inherit;font-size:12px}
.knoxx-lens .merkle-box{display:flex;flex-direction:column;gap:4px;padding:8px;border-radius:6px;background:var(--background-tertiary,#1e1f22);border:1px solid var(--background-modifier-accent,#3f4147);font-size:11px;font-family:var(--font-code,monospace)}
.knoxx-lens .window-card{display:flex;flex-direction:column;gap:5px;padding:8px 10px;border-radius:8px;background:var(--background-primary,#313338);border:1px solid var(--background-modifier-accent,#3f4147);font-size:12px}
.knoxx-lens .window-card:hover{border-color:var(--brand-500,#5865f2)}
.knoxx-lens .window-header{display:flex;align-items:center;justify-content:space-between;gap:6px}
.knoxx-lens .window-title{font-weight:700;color:var(--header-primary,#f2f3f5);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex:1}
.knoxx-lens .window-ocr{font-size:11px;color:var(--text-muted,#949ba4);line-height:1.3;max-height:60px;overflow:hidden;text-overflow:ellipsis;font-style:italic}
.knoxx-lens .people-bridge-item{display:flex;align-items:center;justify-content:space-between;gap:6px;padding:4px 8px;border-radius:6px;background:var(--background-primary,#313338);font-size:11.5px}
.knoxx-lens .timeframe-box{padding:6px 10px;border-radius:6px;background:var(--background-primary,#313338);border:1px solid var(--background-modifier-accent,#3f4147);font-size:11.5px;display:flex;align-items:center;justify-content:space-between}

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

(defn- svg-el
  "Creates an SVG element in svg-ns with attribute map."
  [document tag attrs]
  (let [node (.createElementNS document svg-ns tag)]
    (doseq [[k v] attrs]
      (when (some? v)
        (.setAttribute node (name k) (str v))))
    node))

(defn- button [document label class-name on-click]
  (doto (el document "button" (str "k-btn " class-name) label)
    (.addEventListener "click" (fn [e] (.preventDefault e) (when on-click (on-click))))))

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
    :link (let [a (el document "a" "md-link" label)]
            (.setAttribute a "href" (str href))
            (.setAttribute a "target" "_blank")
            (.setAttribute a "rel" "noopener noreferrer")
            (when (str/includes? (str href) "discord.com/channels/")
              (.addEventListener a "click"
                                 (fn [^js e]
                                   (.preventDefault e)
                                   (try
                                     (let [m (.match (str href) (js/RegExp. "channels/([0-9]+|@me)/([0-9]+)(?:/([0-9]+))?"))]
                                       (if m
                                         (let [gid (aget m 1)
                                               cid (aget m 2)
                                               mid (aget m 3)
                                               nav (bd/global-get "location")]
                                           (set! (.-href nav) (str "/channels/" gid "/" cid (when mid (str "/" mid)))))
                                         (.open js/window (str href) "_blank")))
                                     (catch :default _
                                       (.open js/window (str href) "_blank"))))))
            a)
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

(defn- extract-query-from-preview [preview]
  (let [s (str (or preview ""))]
    (or (second (re-find #"\"query\"\s*:\s*\"([^\"]+)\"" s))
        (second (re-find #":query\s+\"([^\"]+)\"" s)))))

(defn- extract-url-from-preview [preview]
  (let [s (str (or preview ""))]
    (or (second (re-find #"\"url\"\s*:\s*\"([^\"]+)\"" s))
        (second (re-find #":url\s+\"([^\"]+)\"" s)))))

(defn- clean-receipt-output [s]
  (-> (str (or s ""))
      (str/replace #"(?i)<\s*grabbed_context\b[^>]*>[\s\S]*?<\s*/\s*grabbed_context\s*>\s*" "")
      (str/replace #"(?i)<\s*refined_topics\b[^>]*>[\s\S]*?<\s*/\s*refined_topics\s*>\s*" "")
      str/trim))

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

(defn- tool-receipt-node
  [document open-receipts on-explore-graph request-id {:keys [id tool status input-preview updates output-preview]}]
  (let [receipt-key (str (or request-id "turn") ":" (or id tool))
        status-kw (or status :done)
        status-class (name status-kw)
        status-icon (case status-kw :running "⏳" :error "✗" "✓")
        extracted-q (extract-query-from-preview input-preview)
        extracted-url (extract-url-from-preview input-preview)
        cleaned-output (clean-receipt-output output-preview)
        summary-hint (one-line-preview (or extracted-url extracted-q input-preview (first updates) cleaned-output "") 64)
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
    (when (and on-explore-graph (or (= "graph_query" (str tool))
                                    (str/includes? (str tool) "lens_grab_context")
                                    (seq extracted-q)))
      (let [btn (button document "🕸️ Graph" "subtle"
                        #(on-explore-graph (or extracted-q "")))]
        (.addEventListener btn "click" (fn [e] (.stopPropagation e)))
        (.appendChild summary btn)))
    (when (seq (str input-preview))
      (.appendChild body (receipt-section document "Input" input-preview)))
    (when (seq updates)
      (.appendChild body (receipt-section document "Updates" (str/join "\n" updates))))
    (when (seq cleaned-output)
      (.appendChild body (receipt-section document "Result" cleaned-output)))
    (when (and (str/blank? (str input-preview))
               (empty? updates)
               (str/blank? cleaned-output))
      (.appendChild body (el document "div" "tr-section-label"
                             (if (= :running status-kw) "Running tool…" "Completed (no preview payload)"))))
    (.append details summary body)
    details))

(defn- draft-card-node
  [document {:keys [on-stage-draft on-approve-draft channel]} {:keys [id text] draft-ch :channel}]
  (let [target-label (or (not-empty (str draft-ch))
                         (when channel (str "#" (:name channel)))
                         "channel")
        card (el document "div" "draft-card")
        hdr (el document "div" "draft-header")
        left-title (el document "span" nil (str "✍️ Draft Message → " target-label))
        gate-badge (el document "span" "actor-badge" "knoxx_lens · approval gate")
        ta (doto (el document "textarea" "draft-textarea")
             (aset "value" (str (or text ""))))
        actions (el document "div" "draft-actions")
        left-btns (el document "div" "intel-row")
        stage-btn (button document "↘ Stage in Discord" "subtle"
                          #(when on-stage-draft (on-stage-draft (.-value ta))))
        copy-btn (button document "📋 Copy" "subtle"
                         #(do (copy-text! (.-value ta))
                              (bd/toast! "Copied draft to clipboard" "success")))
        armed* (atom false)
        approve-btn (el document "button" "k-btn primary" "✓ Approve & Send")]
    (.addEventListener approve-btn "click"
                       (fn [e]
                         (.preventDefault e)
                         (if-not @armed*
                           (do
                             (reset! armed* true)
                             (set! (.-textContent approve-btn) (str "⚠ Confirm Send to " target-label)))
                           (do
                             (reset! armed* false)
                             (set! (.-textContent approve-btn) "✓ Sent (Approved)")
                             (set! (.-disabled approve-btn) true)
                             (when on-approve-draft
                               (on-approve-draft id (.-value ta)))))))
    (.append hdr left-title gate-badge)
    (.append left-btns stage-btn copy-btn)
    (.append actions left-btns approve-btn)
    (.append card hdr ta actions)
    card))

(defn- turn-node
  [document open-receipts {:keys [on-explore-graph] :as callbacks}
   {:keys [role request-id text status model context error drafts] :as turn}]
  (let [assistant? (= :assistant role)
        node (el document "div" (str "turn " (name role) (when (= :error status) " error")))
        hdr (el document "div" "turn-header")
        who (el document "span" "who" (if assistant? "knoxx_lens · assistant" "you"))
        receipts (when assistant? (normalize-turn-receipts turn))
        body-el (el document "div" "body md-body")
        body-text (cond
                    (= :error status) (str (when (seq text) (str text "\n\n")) "⚠ " error)
                    (and assistant? (str/blank? text) (#{:pending :streaming} status)) "…"
                    :else (str text))
        meta-parts (cond-> []
                     assistant? (conj "actor: knoxx_lens")
                     (seq context) (conj (str "context: " (str/join ", " context)))
                     (seq receipts) (conj (str (count receipts) " tool" (when (> (count receipts) 1) "s")))
                     model (conj (str "model: " model)))]
    (.appendChild hdr who)
    (.appendChild node hdr)
    (when (seq receipts)
      (let [rc-wrap (el document "div" "tool-receipts")]
        (doseq [r receipts]
          (.appendChild rc-wrap (tool-receipt-node document open-receipts on-explore-graph request-id r)))
        (.appendChild node rc-wrap)))
    (render-markdown! document body-el body-text)
    (.appendChild node body-el)
    (when (and assistant? (seq drafts))
      (doseq [d drafts]
        (.appendChild node (draft-card-node document callbacks d))))
    (when (seq meta-parts)
      (.appendChild node (el document "span" "meta" (str/join " · " meta-parts))))
    node))

;; ─────────────────────────────────────────────────────────────
;; Channel Intelligence Strip (shared by Pulse & Graph)
;; ─────────────────────────────────────────────────────────────

(defn- render-intel-card [document {:keys [message-count participants top-terms]} on-pivot-graph]
  (let [card (el document "div" "intel-card")
        top-row (el document "div" "intel-row")]
    (.append top-row
             (el document "span" "intel-label" (str "Channel Map · " (or message-count 0) " cached msgs")))
    (.appendChild card top-row)
    (when (seq participants)
      (let [p-row (el document "div" "intel-row")]
        (.appendChild p-row (el document "span" "intel-label" "Voices:"))
        (doseq [{:keys [author] cnt :count} (take 6 participants)]
          (let [c (el document "span" "chip" (str "@" author " (" cnt ")"))]
            (when on-pivot-graph
              (.addEventListener c "click" (fn [_] (on-pivot-graph author))))
            (.appendChild p-row c)))
        (.appendChild card p-row)))
    (when (seq top-terms)
      (let [t-row (el document "div" "intel-row")]
        (.appendChild t-row (el document "span" "intel-label" "Topics:"))
        (doseq [item (take 8 top-terms)]
          (let [term-str (if (map? item) (str (:term item)) (str item))
                cnt (when (map? item) (:count item))
                c (el document "span" "chip" (str term-str (when cnt (str " · " cnt))))]
            (when on-pivot-graph
              (.addEventListener c "click" (fn [_] (on-pivot-graph term-str))))
            (.appendChild t-row c)))
        (.appendChild card t-row)))
    card))

;; ─────────────────────────────────────────────────────────────
;; ⚡ Pulse View (Automatic & 1-Click Channel Digest)
;; ─────────────────────────────────────────────────────────────

(defn- render-pulse-pane!
  [{:keys [document pulse-pane open-receipts]}
   {:keys [channel channel-summary pulse-conv auto-pulse?
           on-toggle-auto-pulse on-refresh-pulse on-promote-pulse on-explore-graph]
    :as view-map}]
  (set! (.-textContent pulse-pane) "")
  (let [toolbar (el document "div" "pulse-toolbar")
        left (el document "div" "intel-row")
        right (el document "div" "intel-row")
        auto-btn (button document
                         (if auto-pulse? "⚡ Auto-Pulse: ON" "⚡ Auto-Pulse: OFF")
                         (if auto-pulse? "pulse-on" "subtle")
                         on-toggle-auto-pulse)
        refresh-btn (button document
                            (if (some? (:running-request pulse-conv)) "⏳ Pulsing…" "↻ Refresh Pulse")
                            ""
                            on-refresh-pulse)
         turns (:turns pulse-conv)
        asst-turn (last (filter #(= :assistant (:role %)) turns))
        promote-btn (button document "💬 Continue in Chat →" "primary" on-promote-pulse)]
    (set! (.-disabled refresh-btn) (or (nil? channel) (some? (:running-request pulse-conv))))
    (set! (.-disabled promote-btn) (nil? asst-turn))
    (.append left auto-btn refresh-btn)
    (.append right promote-btn)
    (.append toolbar left right)
    (.appendChild pulse-pane toolbar)
    (when (and channel-summary (pos? (:message-count channel-summary 0)))
      (.appendChild pulse-pane (render-intel-card document channel-summary on-explore-graph)))
    (if asst-turn
      (.appendChild pulse-pane (turn-node document open-receipts view-map asst-turn))
      (let [empty-box (el document "div" "empty")]
        (set! (.-textContent empty-box)
              (if channel
                (str "No Pulse digest generated for #" (:name channel) " yet. Click \"↻ Refresh Pulse\" (or pause scrolling with Auto-Pulse ON) for an instant synthesis of active threads, decisions, and graph connections.")
                "Open a Discord channel to generate a live Pulse digest."))
        (.appendChild pulse-pane empty-box)))))

;; ─────────────────────────────────────────────────────────────
;; 🕸️ Graph Memory Explorer View (Interactive SVG + Node/Edge Cards)
;; ─────────────────────────────────────────────────────────────

(defn- node-kind-of [node]
  (str (or (:nodeType node) (:kind node) "node")))

(defn- edge-kind-of [edge]
  (str (or (:edgeKind edge) (:kind edge) "edge")))

(defn- node-kind-color [kind]
  (case (str kind)
    "discord.user" "#00a8fc"
    "discord.channel" "#5865f2"
    "discord.guild" "#eb459e"
    "discord.message" "#23a55a"
    "knoxx.message" "#f0b232"
    "web.url" "#a855f7"
    "#949ba4"))

(defn- short-node-label [node]
  (let [raw (str (or (:label node) (:text node) (:id node) "?"))
        clean (-> raw (str/replace #"\s+" " ") str/trim)]
    (if (> (count clean) 22)
      (str (subs clean 0 21) "…")
      clean)))

(defn- render-graph-svg
  [document {:keys [query nodes edges selected-node-id]} on-select-node]
  (let [wrap (el document "div" "graph-canvas-wrap")
        svg (svg-el document "svg" {:viewBox "0 0 420 210" :class "graph-svg"})
        top-nodes (vec (take 10 nodes))
        cx 210
        cy 105
        r 76
        n (max 1 (count top-nodes))
        positions (into {}
                        (map-indexed
                         (fn [idx node]
                           (let [angle (- (* (/ (* 2 js/Math.PI) n) idx) (/ js/Math.PI 2))
                                 x (+ cx (* r (js/Math.cos angle)))
                                 y (+ cy (* r (js/Math.sin angle)))]
                             [(:id node) {:x x :y y :node node}])))
                        top-nodes)]
    ;; Spokes from center query hub to each top node
    (doseq [[_ {:keys [x y]}] positions]
      (.appendChild svg (svg-el document "line"
                                {:x1 cx :y1 cy :x2 x :y2 y
                                 :stroke "#3f4147" :stroke-width "1" :stroke-dasharray "2,2"})))
    ;; Incident edges between visible nodes
    (doseq [{:keys [source target] :as edge} edges]
      (when-let [p1 (get positions source)]
        (when-let [p2 (get positions target)]
          (let [ek (edge-kind-of edge)
                line (svg-el document "line"
                             {:x1 (:x p1) :y1 (:y p1) :x2 (:x p2) :y2 (:y p2)
                              :stroke "#5865f2" :stroke-width "1.6" :stroke-opacity "0.7"})
                title (svg-el document "title" {})]
            (set! (.-textContent title) (str ek ": " source " → " target))
            (.appendChild line title)
            (.appendChild svg line)))))
    ;; Center query hub
    (let [hub-g (svg-el document "g" {:style "cursor:pointer"})
          hub-circle (svg-el document "circle" {:cx cx :cy cy :r 18 :fill "#5865f2" :stroke "#fff" :stroke-width "1.5"})
          hub-label (svg-el document "text" {:x cx :y (+ cy 4) :text-anchor "middle" :fill "#fff" :font-size "9.5" :font-weight "700"})]
      (set! (.-textContent hub-label)
            (let [q (str (or query "graph"))]
              (if (> (count q) 10) (str (subs q 0 9) "…") q)))
      (.append hub-g hub-circle hub-label)
      (.appendChild svg hub-g))
    ;; Outer nodes
    (doseq [[nid {:keys [x y node]}] positions]
      (let [selected? (= (str nid) (str selected-node-id))
            color (node-kind-color (node-kind-of node))
            g (svg-el document "g" {:style "cursor:pointer"})
            circle (svg-el document "circle"
                           {:cx x :cy y :r (if selected? 11 8.5)
                            :fill color
                            :stroke (if selected? "#ffffff" "#1e1f22")
                            :stroke-width (if selected? "2.2" "1.4")})
            label (svg-el document "text"
                          {:x x :y (if (< y cy) (- y 13) (+ y 19))
                           :text-anchor "middle"
                           :fill "#dbdee1"
                           :font-size "9.5"})]
        (set! (.-textContent label) (short-node-label node))
        (.addEventListener g "click" (fn [_] (when on-select-node (on-select-node node))))
        (.append g circle label)
        (.appendChild svg g)))
    (.appendChild wrap svg)
    (let [legend (el document "div" "graph-legend")]
      (set! (.-textContent legend) "● user  ● channel  ● server  ● msg  ● url  (click node to inspect/pivot)")
      (.appendChild wrap legend))
    wrap))

(defn- render-graph-pane!
  [{:keys [document graph-pane]}
   {:keys [channel channel-summary graph-state knoxx-web-url
           on-graph-search on-select-graph-node on-pin-graph-node]}]
  (set! (.-textContent graph-pane) "")
  (let [{:keys [loading? query nodes edges error selected-node-id]} graph-state
        toolbar (el document "div" "graph-toolbar")
        search-row (el document "div" "graph-search-row")
        search-input (doto (el document "input" "graph-input")
                       (aset "value" (str (or query "")))
                       (aset "placeholder" "Query graph memory (e.g. orbit, @user, #channel)…"))
        search-btn (button document (if loading? "⏳…" "🕸️ Query") "primary"
                           #(when on-graph-search (on-graph-search (.-value search-input))))
        ext-link (doto (el document "a" "k-btn subtle" "↗ Knoxx UI")
                   (.setAttribute "href" (str (or knoxx-web-url "http://127.0.0.1:5174") "/graph"))
                   (.setAttribute "target" "_blank")
                   (.setAttribute "rel" "noopener noreferrer"))]
    (.addEventListener search-input "keydown"
                       (fn [^js e]
                         (when (= "Enter" (.-key e))
                           (.preventDefault e)
                           (when on-graph-search (on-graph-search (.-value search-input))))))
    (.append search-row search-input search-btn ext-link)
    (.appendChild toolbar search-row)
    ;; Quick pivot chips from channel summary
    (let [pivots (el document "div" "intel-row")]
      (when channel
        (let [ch-chip (el document "span" "chip" (str "#" (:name channel)))]
          (.addEventListener ch-chip "click" (fn [_] (when on-graph-search (on-graph-search (:name channel)))))
          (.appendChild pivots ch-chip)))
      (doseq [{:keys [author]} (take 4 (:participants channel-summary))]
        (let [c (el document "span" "chip" (str "@" author))]
          (.addEventListener c "click" (fn [_] (when on-graph-search (on-graph-search author))))
          (.appendChild pivots c)))
      (doseq [item (take 5 (:top-terms channel-summary))]
        (let [term (if (map? item) (str (:term item)) (str item))
              c (el document "span" "chip" term)]
          (.addEventListener c "click" (fn [_] (when on-graph-search (on-graph-search term))))
          (.appendChild pivots c)))
      (when (pos? (.-childElementCount pivots))
        (.appendChild toolbar pivots)))
    (.appendChild graph-pane toolbar)
    (when (seq (str error))
      (.appendChild graph-pane (el document "div" "problems" (str "⚠ " error))))
    (if (seq nodes)
      (do
        (.appendChild graph-pane
                      (render-graph-svg document graph-state
                                        (fn [node]
                                          (when on-select-graph-node (on-select-graph-node node)))))
        (let [edges-by-node (reduce (fn [acc {:keys [source target] :as edge}]
                                      (let [ek (edge-kind-of edge)]
                                        (-> acc
                                            (update (str source) (fnil conj []) (assoc edge :dir "→" :peer target :kind ek))
                                            (update (str target) (fnil conj []) (assoc edge :dir "←" :peer source :kind ek)))))
                                    {}
                                    edges)
              ordered-nodes (if selected-node-id
                              (sort-by #(if (= (str (:id %)) (str selected-node-id)) 0 1) nodes)
                              nodes)]
          (doseq [node (take 18 ordered-nodes)]
            (let [nid (str (:id node))
                  nkind (node-kind-of node)
                  selected? (= nid (str selected-node-id))
                  incident (get edges-by-node nid [])
                  card (el document "div" (str "graph-node-card" (when selected? " selected")))
                  hdr (el document "div" "gn-header")
                  title (el document "span" "gn-title" (str (or (:label node) nid)))
                  kind-badge (el document "span" "gn-kind" nkind)
                  snippet-text (str/trim (str (or (:text node) "")))
                  actions (el document "div" "gn-actions")
                  pivot-btn (button document "🕸️ Pivot" "subtle"
                                    #(when on-graph-search
                                       (on-graph-search (str (or (:label node) nid)))))
                  pin-btn (button document "📌 Pin to Chat" "subtle"
                                  #(when on-pin-graph-node
                                     (on-pin-graph-node node incident)))]
              (set! (.. kind-badge -style -color) (node-kind-color nkind))
              (.append hdr title kind-badge)
              (.appendChild card hdr)
              (when (and (seq snippet-text) (not= snippet-text (str (:label node))))
                (.appendChild card (el document "div" "gn-snippet" (one-line-preview snippet-text 220))))
              (when (seq incident)
                (let [edge-wrap (el document "div" "gn-edges")]
                  (doseq [{:keys [kind dir peer]} (take 6 incident)]
                    (let [pill (el document "span" "gn-edge" (str kind " " dir " " (one-line-preview peer 28)))]
                      (.addEventListener pill "click"
                                         (fn [_]
                                           (when on-graph-search (on-graph-search (str peer)))))
                      (.appendChild edge-wrap pill)))
                  (.appendChild card edge-wrap)))
              (.append actions pivot-btn pin-btn)
              (.appendChild card actions)
              (.appendChild graph-pane card)))))
      (let [empty-box (el document "div" "empty")]
        (set! (.-textContent empty-box)
              (if loading?
                "Querying OpenPlanner semantic & structural graph memory…"
                "Enter a query or click any topic/voice chip above to explore semantically related nodes and their incident edges (authored_by, in_channel, mentions_user, replies_to, mentions_web_url)."))
        (.appendChild graph-pane empty-box)))))

(defn- render-connections-pane!
  [{:keys [document connections-pane webgl-view-atom]}
   {:keys [conn-graph on-conn-query on-conn-refresh on-conn-node-click on-desktop-focus]}]
  (let [{:keys [query nodes edges people-bridges timeframe selected-node error]} conn-graph]
    (set! (.-textContent connections-pane) "")
    (let [toolbar (el document "div" "graph-toolbar")
          search-row (el document "div" "graph-search-row")
          input (doto (el document "input" "graph-input")
                  (aset "placeholder" "Explore connections across servers & Bluesky…")
                  (aset "value" (str (or query ""))))
          q-btn (button document "🌐 Query" "primary" #(when on-conn-query (on-conn-query (.-value input))))
          r-btn (button document "↻ Refresh" "" #(when on-conn-refresh (on-conn-refresh (.-value input))))]
      (.addEventListener input "keydown" (fn [^js e] (when (= "Enter" (.-key e)) (.preventDefault e) (when on-conn-query (on-conn-query (.-value input))))))
      (.append search-row input q-btn r-btn)
      (.appendChild toolbar search-row)
      (.appendChild connections-pane toolbar))
    (when (seq (str error))
      (.appendChild connections-pane (el document "div" "problems" (str "⚠ " error))))
    (let [canvas-wrap (el document "div" "conn-canvas-wrap")
          canvas (el document "canvas" "knoxx-conn-canvas")]
      (set! (.-width canvas) 436)
      (set! (.-height canvas) 320)
      (.appendChild canvas-wrap canvas)
      (let [legend (el document "div" "graph-legend" (str "Nodes: " (count nodes) " · Edges: " (count edges) " · WebGL Shaders"))]
        (.appendChild canvas-wrap legend))
      (.appendChild connections-pane canvas-wrap)
      (let [view-wrapper (webgl-view/create-view canvas {:on-node-click on-conn-node-click})]
        (reset! webgl-view-atom view-wrapper)
        (when (seq nodes)
          (webgl-view/set-graph! view-wrapper nodes edges))))
    (when selected-node
      (let [card (el document "div" "graph-node-card selected")
            hdr (el document "div" "gn-header")
            title (el document "span" "gn-title" (str (or (:label selected-node) (:id selected-node))))
            kind-badge (el document "span" "gn-kind" (str (or (:kind selected-node) "node")))
            actions (el document "div" "gn-actions")
            is-window? (= "desktop.window" (:kind selected-node))
            permalink-url (get-in selected-node [:data :permalink])
            bsky-url (get-in selected-node [:data :uri])]
        (.append hdr title kind-badge)
        (.appendChild card hdr)
        (when is-window?
          (let [focus-btn (button document "🖥️ Focus Window in i3" "primary"
                                  #(when on-desktop-focus
                                     (on-desktop-focus (or (get-in selected-node [:data :wid]) (:id selected-node)))))]
            (.appendChild actions focus-btn)))
        (when permalink-url
          (let [link-btn (button document "🔗 Open Message in Discord" "subtle"
                                 #(try (.open js/window permalink-url "_blank") (catch :default _ nil)))]
            (.appendChild actions link-btn)))
        (when bsky-url
          (let [bsky-btn (button document "🦋 Open on Bluesky" "subtle"
                                 #(try (.open js/window bsky-url "_blank") (catch :default _ nil)))]
            (.appendChild actions bsky-btn)))
        (when (pos? (count (.-childNodes actions)))
          (.appendChild card actions))
        (.appendChild connections-pane card)))
    (when (seq people-bridges)
      (let [bridges-card (el document "div" "intel-card")
            hdr (el document "div" "intel-row")
            lbl (el document "span" "intel-label" "👥 Cross-Server & Bluesky People Bridges")]
        (.appendChild hdr lbl)
        (.appendChild bridges-card hdr)
        (doseq [bridge (take 5 people-bridges)]
          (let [row (el document "div" "people-bridge-item")
                user-info (el document "span" nil (str "👤 " (:username bridge) " (" (:handle bridge) ")"))
                guilds-info (el document "span" "badge" (str (count (:guilds bridge)) " servers · " (:message_count bridge) " msgs"))]
            (.append row user-info guilds-info)
            (.appendChild bridges-card row)))
        (.appendChild connections-pane bridges-card)))
    (when timeframe
      (let [tf-box (el document "div" "timeframe-box")
            tf-label (el document "span" nil (str "⏱️ Timeframe: " (or (:first_iso timeframe) "") " → " (or (:last_iso timeframe) "")))
            tf-links (el document "div" "gn-actions")]
        (when-let [p1 (:first_permalink timeframe)]
          (.appendChild tf-links (button document "First" "subtle" #(try (.open js/window p1 "_blank") (catch :default _ nil)))))
        (when-let [p2 (:last_permalink timeframe)]
          (.appendChild tf-links (button document "Last" "subtle" #(try (.open js/window p2 "_blank") (catch :default _ nil)))))
        (.append tf-box tf-label tf-links)
        (.appendChild connections-pane tf-box)))))

(defn- render-extract-pane!
  [{:keys [document extract-pane]}
   {:keys [extract-state channel channel-buffer on-govone-extract on-govone-send on-govone-refresh-channel]}]
  (let [{:keys [text sanitized merkle-root dek-shredded messages error]} extract-state]
    (set! (.-textContent extract-pane) "")
    (let [card (el document "div" "intel-card")
          hdr (el document "div" "intel-row")
          title (el document "span" "intel-label" "🛡️ GovOne Sanitized Extraction")
          desc (el document "div" "empty" "Extracts Discord messages while stripping usernames, mentions, IDs, and PII into [[ENTITY_N]] placeholders with Merkle audit evidence and DEK crypto-shredding.")
          textarea (doto (el document "textarea" "extract-textarea")
                     (aset "placeholder" "Paste Discord message text or click 'Load Current Channel'...")
                     (aset "rows" "4")
                     (aset "value" (str (or text ""))))
          btn-row (el document "div" "intel-row")
          load-chan-btn (button document "📋 Load Current Channel" "subtle"
                                #(when (and channel channel-buffer)
                                   (set! (.-value textarea) (str channel-buffer))))
          run-btn (button document "🔒 Sanitize with GovOne" "primary"
                          #(when on-govone-extract
                             (on-govone-extract (.-value textarea))))]
      (.appendChild hdr title)
      (.append btn-row load-chan-btn run-btn)
      (.append card hdr desc textarea btn-row)
      (.appendChild extract-pane card))
    (when (seq (str error))
      (.appendChild extract-pane (el document "div" "problems" (str "⚠ " error))))
    (when (seq (str sanitized))
      (let [res-card (el document "div" "intel-card")
            lbl (el document "span" "intel-label" "📄 Masked / Sanitized Content")
            pre (el document "div" "extract-preview" sanitized)
            merkle-box (el document "div" "merkle-box")
            m-root (el document "span" nil (str "🔏 Merkle Root: " (or merkle-root "0x000...")))
            dek-badge (el document "span" (if dek-shredded "badge ready" "badge blocked")
                          (if dek-shredded "✅ Session DEK Crypto-Shredded via Terminate" "⚠️ Session Active"))]
        (.append merkle-box m-root dek-badge)
        (.append res-card lbl pre merkle-box)
        (.appendChild extract-pane res-card)))
    (let [chan-card (el document "div" "intel-card")
          chan-hdr (el document "div" "intel-row")
          chan-title (el document "span" "intel-label" "💬 Governed Channel Bridge")
          chan-refresh (button document "↻" "subtle" #(when on-govone-refresh-channel (on-govone-refresh-channel)))
          msg-input (doto (el document "input" "graph-input")
                      (aset "placeholder" "Message social agent over governed channel..."))
          send-btn (button document "Send" "primary"
                           #(let [v (.-value msg-input)]
                              (when (and (seq v) on-govone-send)
                                (on-govone-send v)
                                (set! (.-value msg-input) ""))))]
      (.append chan-hdr chan-title chan-refresh)
      (.appendChild chan-card chan-hdr)
      (if (seq messages)
        (doseq [m (take 6 messages)]
          (let [m-row (el document "div" "people-bridge-item")
                content (el document "span" nil (str "💬 " (:content m)))
                pri (el document "span" "badge" (or (:priority m) "normal"))]
            (.append m-row content pri)
            (.appendChild chan-card m-row)))
        (.appendChild chan-card (el document "div" "empty" "No messages on governed channel yet.")))
      (let [input-row (el document "div" "graph-search-row")]
        (.append input-row msg-input send-btn)
        (.appendChild chan-card input-row))
      (.appendChild extract-pane chan-card))))

(defn- render-windows-pane!
  [{:keys [document windows-pane]}
   {:keys [desktop-windows on-desktop-refresh on-desktop-focus on-pin-window]}]
  (set! (.-textContent windows-pane) "")
  (let [toolbar (el document "div" "graph-toolbar")
        lbl (el document "span" "intel-label" "🖥️ Active Desktop Windows (i3 & NPU OCR)")
        ref-btn (button document "↻ Refresh Windows" "" #(when on-desktop-refresh (on-desktop-refresh)))]
    (.append toolbar lbl ref-btn)
    (.appendChild windows-pane toolbar))
  (if (seq desktop-windows)
    (doseq [win desktop-windows]
      (let [card (el document "div" "window-card")
            hdr (el document "div" "window-header")
            title (el document "span" "window-title" (str (or (:class win) "Window") ": " (or (:title win) "")))
            ws-badge (el document "span" "badge" (str "WS " (or (:workspace win) "1")))
            ocr-text (str/trim (str (or (:ocrText win) "")))
            actions (el document "div" "gn-actions")
            focus-btn (button document "🎯 Focus in i3" "primary"
                              #(when on-desktop-focus (on-desktop-focus (or (:wid win) (:id win)))))
            pin-btn (button document "📌 Pin Context" "subtle"
                            #(when on-pin-window (on-pin-window win)))]
        (.append hdr title ws-badge)
        (.appendChild card hdr)
        (when (seq ocr-text)
          (let [ocr-preview (el document "div" "window-ocr" (str "OCR: " (one-line-preview ocr-text 160)))]
            (.appendChild card ocr-preview)))
        (.append actions focus-btn pin-btn)
        (.appendChild card actions)
        (.appendChild windows-pane card)))
    (.appendChild windows-pane (el document "div" "empty" "No indexed windows found in ~/.cache/desktop-index/state.edn."))))

(defn- render-telemetry-bar!
  [{:keys [document telemetry-bar]}
   {:keys [telemetry running?]}]
  (when telemetry-bar
    (let [{:keys [phase tool-name ttft-ms tokens-per-s input-tokens output-tokens]} telemetry]
      (set! (.-textContent telemetry-bar) "")
      (let [phase-kw (or phase (if running? :waiting :idle))
            phase-label (case phase-kw
                          :waiting "⏳ Waiting for server"
                          :thinking "🧠 Thinking"
                          :tool (str "🔧 Tool: " (or tool-name "active"))
                          :streaming "✍️ Streaming"
                          :idle "● Ready"
                          "● Ready")
            phase-badge (el document "span" (str "telemetry-phase " (name phase-kw)) phase-label)]
        (.appendChild telemetry-bar phase-badge))
      (when (and ttft-ms (pos? ttft-ms))
        (.appendChild telemetry-bar (el document "span" nil (str "TTFT: " ttft-ms "ms"))))
      (when (and tokens-per-s (pos? tokens-per-s))
        (.appendChild telemetry-bar (el document "span" nil (str (.toFixed (js/Number. tokens-per-s) 1) " tok/s"))))
      (when (or (pos? (or input-tokens 0)) (pos? (or output-tokens 0)))
        (.appendChild telemetry-bar (el document "span" nil (str "In: " (or input-tokens 0) " Out: " (or output-tokens 0))))))))

;; ─────────────────────────────────────────────────────────────
;; Drawer mount & render
;; ─────────────────────────────────────────────────────────────

(defn mount!
  "Builds the drawer and toggle tab, appends them to document.body, and returns refs."
  [{:keys [on-toggle on-close on-new on-tab-change
           on-history-toggle on-history-filter on-history-refresh
           on-send on-stop]}]
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
        mode-tabs (el document "div" "mode-tabs")
        tab-chat (el document "button" "mode-tab active")
        tab-pulse (el document "button" "mode-tab")
        tab-graph (el document "button" "mode-tab")
        tab-connections (el document "button" "mode-tab")
        tab-extract (el document "button" "mode-tab")
        tab-windows (el document "button" "mode-tab")
        telemetry-bar (el document "div" "telemetry-bar")
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
        pulse-pane (el document "div" "pulse-pane")
        graph-pane (el document "div" "graph-pane")
        connections-pane (el document "div" "connections-pane")
        extract-pane (el document "div" "extract-pane")
        windows-pane (el document "div" "windows-pane")
        webgl-view-atom (atom nil)
        suggestions-tray (el document "div" "suggestions-tray")
        footer (el document "footer")
        input (doto (el document "textarea")
                (aset "placeholder" "Ask knoxx_lens or request a draft… (Enter=send, Shift+Enter=newline, @last:N)"))
        send-btn (button document "Send" "primary" #(on-send (.-value input)))
        stop-btn (button document "Stop" "" on-stop)]
    (set! (.-className toggle) "knoxx-lens-toggle")
    (when add-style (.call add-style (u/jget (bd/bd-api) "DOM") style-id css))
    (.addEventListener tab-chat "click" (fn [e] (.preventDefault e) (when on-tab-change (on-tab-change :chat))))
    (.addEventListener tab-pulse "click" (fn [e] (.preventDefault e) (when on-tab-change (on-tab-change :pulse))))
    (.addEventListener tab-graph "click" (fn [e] (.preventDefault e) (when on-tab-change (on-tab-change :graph))))
    (.addEventListener tab-connections "click" (fn [e] (.preventDefault e) (when on-tab-change (on-tab-change :connections))))
    (.addEventListener tab-extract "click" (fn [e] (.preventDefault e) (when on-tab-change (on-tab-change :extract))))
    (.addEventListener tab-windows "click" (fn [e] (.preventDefault e) (when on-tab-change (on-tab-change :windows))))
    (.addEventListener input "keydown"
                       (fn [^js e]
                         (when (and (= "Enter" (.-key e)) (not (.-shiftKey e)))
                           (.preventDefault e)
                           (on-send (.-value input)))))
    (.append mode-tabs tab-chat tab-pulse tab-graph tab-connections tab-extract tab-windows)
    (.append bar-actions filter-btn refresh-btn)
    (.append sessions-bar sessions-label bar-actions)
    (.append sessions-drawer sessions-bar sessions-list)
    (.append header title status history-btn new-btn close-btn)
    (.append footer input send-btn stop-btn)
    (.append root header telemetry-bar mode-tabs sessions-drawer chips budget problems transcript pulse-pane graph-pane connections-pane extract-pane windows-pane suggestions-tray footer)
    (.append (.-body document) toggle root)
    {:document document :root root :toggle toggle :title title :status status
     :tab-chat tab-chat :tab-pulse tab-pulse :tab-graph tab-graph
     :tab-connections tab-connections :tab-extract tab-extract :tab-windows tab-windows
     :telemetry-bar telemetry-bar
     :connections-pane connections-pane :extract-pane extract-pane :windows-pane windows-pane
     :webgl-view-atom webgl-view-atom
     :history-btn history-btn :sessions-drawer sessions-drawer
     :sessions-label sessions-label :filter-btn filter-btn :sessions-list sessions-list
     :chips chips :budget budget :problems problems
     :transcript transcript :pulse-pane pulse-pane :graph-pane graph-pane
     :suggestions-tray suggestions-tray
     :footer footer :input input :send send-btn :stop stop-btn
     :open-receipts (js/Set.)}))

(defn unmount!
  "Removes the drawer, toggle tab, and stylesheet."
  [{:keys [root toggle webgl-view-atom]}]
  (when webgl-view-atom
    (when-let [w @webgl-view-atom]
      (webgl-view/destroy! w)
      (reset! webgl-view-atom nil)))
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
      (when on-click (.addEventListener chip "click" (fn [_] (on-click))))
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

(defn- render-mode-tabs!
  [{:keys [document tab-chat tab-pulse tab-graph tab-connections tab-extract tab-windows]}
   {:keys [active-tab turns pulse-conv graph-state conn-graph desktop-windows extract-state]}]
  (let [tab (or active-tab :chat)
        chat-count (count (filter #(= :assistant (:role %)) turns))
        pulse-running? (some? (:running-request pulse-conv))
        pulse-ready? (seq (:turns pulse-conv))
        graph-count (count (:nodes graph-state))
        conn-count (count (:nodes conn-graph))
        win-count (count desktop-windows)
        extracted? (seq (:sanitized extract-state))]
    (doseq [[el-node kw label pill]
            [[tab-chat :chat "💬 Chat" (when (pos? chat-count) (str chat-count))]
             [tab-pulse :pulse "⚡ Pulse" (cond pulse-running? "⏳" pulse-ready? "✓" :else nil)]
             [tab-graph :graph "🕸️ Graph" (when (pos? graph-count) (str graph-count))]
             [tab-connections :connections "🌐 Conns" (when (pos? conn-count) (str conn-count))]
             [tab-extract :extract "🛡️ Extract" (when extracted? "✓")]
             [tab-windows :windows "🖥️ Windows" (when (pos? win-count) (str win-count))]]]
      (when el-node
        (.toggle (.-classList el-node) "active" (= tab kw))
        (set! (.-textContent el-node) "")
        (.appendChild el-node (el document "span" nil label))
        (when pill
          (.appendChild el-node (el document "span" "tab-pill" pill)))))))


(defn- render-suggestions-tray!
  [{:keys [document suggestions-tray]}
   {:keys [active-tab active-suggestions suggestions-from-turn? on-use-suggestion]}]
  (let [tab (or active-tab :chat)
        show? (and (not= :graph tab) (seq active-suggestions))]
    (set! (.. suggestions-tray -style -display) (if show? "flex" "none"))
    (set! (.-textContent suggestions-tray) "")
    (when show?
      (let [hdr (el document "div" "suggestions-tray-hdr")
            lbl (el document "span" nil (if suggestions-from-turn?
                                          "✨ Suggested Follow-Ups · Actor: knoxx_lens"
                                          "💡 Channel Topic Starters · Actor: knoxx_lens"))
            hint (el document "span" nil "Click=Ask · Shift+Click=Edit")
            list-el (el document "div" "suggestions-tray-list")]
        (.append hdr lbl hint)
        (doseq [s (take 4 active-suggestions)]
          (let [icon (cond
                       (re-find #"(?i)^draft\b" s) "✍️ "
                       (re-find #"(?i)\bgraph\b|\bmemory\b" s) "🕸️ "
                       :else "💡 ")
                pill (el document "button" "sugg-pill" (str icon (one-line-preview s 68)))]
            (.setAttribute pill "title" s)
            (.addEventListener pill "click"
                               (fn [^js e]
                                 (.preventDefault e)
                                 (when on-use-suggestion
                                   (on-use-suggestion s (boolean (.-shiftKey e))))))
            (.appendChild list-el pill)))
        (.append suggestions-tray hdr list-el)))))

(defn render!
  "Re-renders header, mode tabs, session history, chips, budget, problems, suggestions tray, and active pane from view."
  [{:keys [document root toggle title status chips budget problems
           transcript pulse-pane graph-pane connections-pane extract-pane windows-pane footer send stop open-receipts] :as refs}
   {:keys [open? active-tab channel connected? lens-config chips-list budget-text over-budget?
           turns running? on-explore-graph on-refresh-pulse] :as view-map}]
  (let [tab (or active-tab :chat)
        ready? (and connected? (:ready lens-config))]
    (.toggle (.-classList root) "open" (boolean open?))
    (set! (.. toggle -style -display) (if open? "none" "block"))
    (set! (.-textContent title) (str "Knoxx Lens · " (if channel (str "#" (:name channel)) "no channel")))
    (set! (.-textContent status) (cond (not connected?) "bot offline"
                                       ready? (str "knoxx_lens · " (:model lens-config))
                                       :else "blocked"))
    (.setAttribute status "title" "Personal Assistant Actor: knoxx_lens (distinct from autonomous discord_automation bot)")
    (set! (.-className status) (str "badge " (if ready? "ready" "blocked")))
    (set! (.-disabled send) (or running? (not ready?) (nil? channel)))
    (set! (.-disabled stop) (not running?))
    (render-telemetry-bar! refs view-map)
    (render-mode-tabs! refs view-map)
    (render-sessions! refs view-map)
    (render-suggestions-tray! refs view-map)
    (set! (.-textContent problems) (str/join "\n" (:problems lens-config)))
    (set! (.. problems -style -display) (if (seq (:problems lens-config)) "block" "none"))
    (render-chips! refs (or chips-list (:chips view-map)))
    (set! (.-textContent budget) budget-text)
    (.toggle (.-classList budget) "warn" (boolean over-budget?))
    ;; Show/hide panes based on active-tab
    (set! (.. chips -style -display) (if (#{:chat :pulse} tab) "flex" "none"))
    (set! (.. budget -style -display) (if (#{:chat :pulse} tab) "block" "none"))
    (set! (.. transcript -style -display) (if (= :chat tab) "flex" "none"))
    (set! (.. pulse-pane -style -display) (if (= :pulse tab) "flex" "none"))
    (set! (.. graph-pane -style -display) (if (= :graph tab) "flex" "none"))
    (set! (.. connections-pane -style -display) (if (= :connections tab) "flex" "none"))
    (set! (.. extract-pane -style -display) (if (= :extract tab) "flex" "none"))
    (set! (.. windows-pane -style -display) (if (= :windows tab) "flex" "none"))
    (set! (.. footer -style -display) (if (#{:chat :pulse} tab) "flex" "none"))
    (case tab
      :pulse (render-pulse-pane! refs view-map)
      :graph (render-graph-pane! refs view-map)
      :connections (render-connections-pane! refs view-map)
      :extract (render-extract-pane! refs view-map)
      :windows (render-windows-pane! refs view-map)
      (let [near-bottom? (< (- (.-scrollHeight transcript) (.-scrollTop transcript) (.-clientHeight transcript)) 40)]
        (set! (.-textContent transcript) "")
        (if (seq turns)
          (doseq [turn turns]
            (.appendChild transcript (turn-node document open-receipts view-map turn)))
          (let [empty-wrap (el document "div" "empty")
                starters (el document "div" "quick-starters")]
            (set! (.-textContent empty-wrap)
                  "Your personal `knoxx_lens` assistant is ready. Right-click any message → \"Add to Knoxx context\" to pin it, click a suggestion below, or ask to draft a reply.")
            (when channel
              (.append starters
                       (button document "⚡ What's going on here?" "subtle"
                               #(when on-refresh-pulse (on-refresh-pulse)))
                       (button document "🕸️ Explore channel graph" "subtle"
                               #(when on-explore-graph (on-explore-graph (:name channel))))))
            (.appendChild empty-wrap starters)
            (.appendChild transcript empty-wrap)))
        (when near-bottom? (set! (.-scrollTop transcript) (.-scrollHeight transcript)))))))

(defn set-input!
  "Sets composer text and focuses it for editing."
  [{:keys [input]} text]
  (set! (.-value input) (str text))
  (.focus input))

(defn clear-input!
  "Clears and refocuses the composer."
  [{:keys [input]}]
  (set! (.-value input) "")
  (.focus input))
