/**
 * @name UXX Discord
 * @author open-hax
 * @description Discord skin driven by the live @open-hax/uxx token library.
 *              Change your uxx theme → Discord updates automatically.
 * @version 1.0.0
 * @source https://github.com/open-hax/uxx
 */

// ─────────────────────────────────────────────────────────────
//  BetterDiscord meta (BD reads the JSDoc block above)
// ─────────────────────────────────────────────────────────────

const UXX_LOCAL_URLS = [
  // Prefer the workspace package build: it is the live npm package source in this checkout.
  'file:///home/err/devel/orgs/open-hax/uxx/dist/tokens/src/index.js',
  // Fallback to the root workspace symlink/install when present.
  'file:///home/err/devel/node_modules/.pnpm/node_modules/@open-hax/uxx/dist/tokens/src/index.js',
];
const UXX_CDN = 'https://esm.sh/@open-hax/uxx@latest/tokens';

// ─────────────────────────────────────────────────────────────
//  Discord → UXX variable mapping
//
//  Everything on the right is a --uxx-* CSS variable produced by
//  getThemeCssVars(themePack) from tokens/src/theme.ts.
//  Paths follow: --uxx-{category}-{...keys} (kebab-cased).
//
//  Color token paths (from getThemeCssVars):
//    colors.background.default  →  --uxx-colors-background-default
//    colors.text.default        →  --uxx-colors-text-default
//    palette.bg.default         →  --uxx-palette-bg-default   (raw palette)
//    radius.md                  →  --uxx-radius-md
//    fontFamily.sans            →  --uxx-font-family-sans
//    fontFamily.mono            →  --uxx-font-family-mono
// ─────────────────────────────────────────────────────────────
const DISCORD_VARS = `
  /* ── Surfaces ── */
  --background-primary:             var(--uxx-colors-background-default);
  --background-secondary:           var(--uxx-colors-background-surface);
  --background-secondary-alt:       var(--uxx-palette-bg-darker);
  --background-tertiary:            var(--uxx-palette-bg-darker);
  --background-accent:              var(--uxx-colors-background-elevated);
  --background-floating:            var(--uxx-colors-background-surface);
  --background-modifier-hover:      var(--uxx-colors-background-hover);
  --background-modifier-active:     rgba(from var(--uxx-palette-accent-cyan) r g b / 0.14);
  --background-modifier-selected:   var(--uxx-colors-selection-default);
  --background-modifier-accent:     rgba(from var(--uxx-palette-bg-selection) r g b / 0.48);

  /* ── Discord visual-refresh / newer token names ── */
  --bg-base-primary:                var(--uxx-colors-background-default);
  --bg-base-secondary:              var(--uxx-colors-background-surface);
  --bg-base-tertiary:               var(--uxx-palette-bg-darker);
  --bg-surface-overlay:             var(--uxx-colors-background-surface);
  --background-base-lowest:         var(--uxx-palette-bg-darker);
  --background-base-lower:          var(--uxx-colors-background-surface);
  --background-base-low:            var(--uxx-colors-background-default);
  --background-surface-highest:     var(--uxx-colors-background-elevated);
  --background-surface-higher:      var(--uxx-colors-background-surface);
  --background-surface-high:        var(--uxx-colors-background-surface);
  --background-surface-normal:      var(--uxx-colors-background-default);
  --background-surface-low:         var(--uxx-palette-bg-darker);
  --background-surface-lower:       var(--uxx-palette-bg-darker);
  --background-surface-lowest:      var(--uxx-palette-bg-darker);
  --chat-background-default:        var(--uxx-colors-background-default);
  --channeltextarea-background:     var(--uxx-colors-background-elevated);
  --custom-channel-members-bg:      var(--uxx-colors-background-surface);
  --custom-guild-list-width:        72px;

  /* ── Text ── */
  --text-normal:      var(--uxx-colors-text-default);
  --text-muted:       var(--uxx-colors-text-muted);
  --text-link:        var(--uxx-colors-text-accent);
  --text-positive:    var(--uxx-colors-semantic-success);
  --text-warning:     var(--uxx-colors-semantic-warning);
  --text-danger:      var(--uxx-colors-semantic-error);
  --text-brand:       var(--uxx-colors-interactive-default);
  --header-primary:   var(--uxx-colors-text-default);
  --header-secondary: var(--uxx-colors-text-panel);

  /* ── Interactive ── */
  --interactive-normal:  var(--uxx-colors-text-panel);
  --interactive-hover:   var(--uxx-colors-text-default);
  --interactive-active:  var(--uxx-colors-text-bright);
  --interactive-muted:   var(--uxx-colors-text-subtle);

  /* ── Channels ── */
  --channels-default:              var(--uxx-colors-text-muted);
  --channel-icon:                  var(--uxx-colors-text-muted);
  --channel-text-area-placeholder: var(--uxx-colors-text-subtle);

  /* ── Brand (Discord's own accent slot) ── */
  --brand-experiment:     var(--uxx-colors-interactive-default);
  --brand-experiment-330: var(--uxx-colors-interactive-default);
  --brand-experiment-360: var(--uxx-colors-interactive-default);
  --brand-experiment-400: var(--uxx-colors-interactive-default);
  --brand-experiment-430: var(--uxx-colors-interactive-hover);
  --brand-experiment-460: var(--uxx-colors-interactive-active);

  /* ── Status ── */
  --status-positive-text:        var(--uxx-colors-semantic-success);
  --status-positive-background:  var(--uxx-colors-badge-success-bg);
  --status-warning-text:         var(--uxx-colors-semantic-warning);
  --status-warning-background:   var(--uxx-colors-badge-warning-bg);
  --status-danger-text:          var(--uxx-colors-semantic-error);
  --status-danger-background:    var(--uxx-colors-badge-error-bg);

  /* ── Borders ── */
  --background-modifier-border:       var(--uxx-colors-border-default);
  --background-modifier-border-hover: var(--uxx-colors-border-subtle);

  /* ── Mentions ── */
  --mention-foreground: var(--uxx-colors-text-accent);
  --mention-background: var(--uxx-colors-badge-info-bg);
`;

// Component CSS — all values reference the --uxx-* variables above,
// so they are theme-agnostic and update when the token pack changes.
const COMPONENT_CSS = `
/* ── App shell uses raw palette bg so surfaces layer correctly ── */
#app-mount, .app-2rEoOp, [class*="app-"] {
  background: var(--uxx-palette-bg-darker) !important;
}
[class*="guilds-"], nav[class*="guildsNavItem-"] {
  background: var(--uxx-palette-bg-darker) !important;
}
[class*="sidebar-"] {
  background: var(--uxx-colors-background-surface) !important;
}
[class*="chat-"] {
  background: var(--uxx-colors-background-default) !important;
}
[class*="membersWrap-"], [class*="membersList-"] {
  background: var(--uxx-colors-background-surface) !important;
}
[class*="panels-"] {
  background: var(--uxx-palette-bg-darker) !important;
  border-top: 1px solid var(--uxx-colors-border-default) !important;
}

/* ── Scrollbars ── */
::-webkit-scrollbar { width: 6px; height: 6px; }
::-webkit-scrollbar-thumb {
  background: var(--uxx-palette-bg-lighter);
  border-radius: var(--uxx-radius-full);
}
::-webkit-scrollbar-thumb:hover { background: var(--uxx-colors-text-subtle); }
::-webkit-scrollbar-track { background: transparent; }

/* ── Code ── */
code, pre, .markup-2BOw-j code {
  font-family: var(--uxx-font-family-mono) !important;
  background: var(--uxx-palette-bg-default) !important;
  border: 1px solid var(--uxx-colors-border-default) !important;
  border-radius: var(--uxx-radius-md) !important;
  color: var(--uxx-colors-text-default) !important;
}
.markup-2BOw-j code {
  padding: 1px 5px !important;
  font-size: var(--uxx-font-size-sm) !important;
}

/* ── Links ── */
a { color: var(--uxx-colors-text-accent) !important; }
a:hover { filter: brightness(1.15); text-decoration: underline; }

/* ── Mentions ── */
.mention, .wrapper-3WhCwL {
  background: var(--uxx-colors-badge-info-bg) !important;
  color: var(--uxx-colors-text-accent) !important;
  border-radius: var(--uxx-radius-sm) !important;
  padding: 0 3px !important;
}
.mention:hover {
  filter: brightness(1.15);
  text-decoration: none !important;
}

/* ── Buttons ── */
[class*="lookFilled"][class*="colorBrand"],
[class*="lookFilled"][class*="colorGreen"] {
  background: var(--uxx-colors-button-primary-bg) !important;
  color: var(--uxx-colors-button-primary-fg) !important;
  border-radius: var(--uxx-radius-md) !important;
  font-family: var(--uxx-font-family-sans) !important;
  font-weight: 500 !important;
  transition: background 120ms ease;
}
[class*="lookFilled"][class*="colorBrand"]:hover {
  background: var(--uxx-colors-button-primary-hover) !important;
}
[class*="lookFilled"][class*="colorRed"],
[class*="lookFilled"][class*="colorDanger"] {
  background: var(--uxx-colors-button-danger-bg) !important;
  color: var(--uxx-colors-button-danger-fg) !important;
  border-radius: var(--uxx-radius-md) !important;
}

/* ── Inputs ── */
[class*="input-"] {
  background: var(--uxx-colors-background-surface) !important;
  border-color: var(--uxx-colors-border-default) !important;
  border-radius: var(--uxx-radius-md) !important;
  color: var(--uxx-colors-text-default) !important;
  font-family: var(--uxx-font-family-sans) !important;
  transition: border-color 120ms ease;
}
[class*="input-"]:focus-within {
  border-color: var(--uxx-colors-border-focus) !important;
}
[class*="textArea-"] {
  background: var(--uxx-colors-background-elevated) !important;
  border-radius: var(--uxx-radius-lg) !important;
  border: 1px solid var(--uxx-colors-border-default) !important;
  font-family: var(--uxx-font-family-sans) !important;
}

/* ── Channel rows ── */
[class*="containerDefault-"] {
  border-radius: var(--uxx-radius-md) !important;
  transition: background 120ms ease;
}
[class*="containerDefault-"]:hover {
  background: var(--uxx-colors-background-hover) !important;
}
[class*="selected-"] {
  background: rgba(from var(--uxx-colors-interactive-default) r g b / 0.12) !important;
  border-radius: var(--uxx-radius-md) !important;
}

/* ── Server icon pills ── */
[class*="pill-"] span { background: var(--uxx-colors-interactive-default) !important; }

/* ── Reactions ── */
[class*="reaction-"] {
  background: var(--uxx-colors-background-elevated) !important;
  border: 1px solid var(--uxx-colors-border-default) !important;
  border-radius: var(--uxx-radius-xl) !important;
  transition: background 120ms ease, border-color 120ms ease;
}
[class*="reaction-"]:hover {
  background: var(--uxx-colors-background-hover) !important;
  border-color: var(--uxx-colors-border-focus) !important;
}
[class*="reactionMe-"] {
  background: rgba(from var(--uxx-colors-interactive-default) r g b / 0.14) !important;
  border-color: var(--uxx-colors-interactive-default) !important;
}

/* ── Tooltips ── */
[class*="tooltip-"] {
  background: var(--uxx-colors-background-surface) !important;
  border: 1px solid var(--uxx-colors-border-default) !important;
  border-radius: var(--uxx-radius-md) !important;
  color: var(--uxx-colors-text-default) !important;
  font-family: var(--uxx-font-family-sans) !important;
}

/* ── Badges ── */
[class*="badge-"], [class*="numberBadge-"] {
  background: var(--uxx-colors-semantic-error) !important;
  color: var(--uxx-colors-text-bright) !important;
  border-radius: var(--uxx-radius-full) !important;
  font-family: var(--uxx-font-family-sans) !important;
  font-weight: 700 !important;
}

/* ── Status dots ── */
[class*="status-"][class*="online-"]  { background: var(--uxx-colors-status-alive) !important; }
[class*="status-"][class*="idle-"]    { background: var(--uxx-colors-semantic-warning) !important; }
[class*="status-"][class*="dnd-"]     { background: var(--uxx-colors-semantic-error) !important; }

/* ── Dividers ── */
[class*="divider-"] { border-color: var(--uxx-colors-border-default) !important; }

/* ── Selection ── */
::selection {
  background: var(--uxx-colors-selection-default);
  color: var(--uxx-colors-text-default);
}
`;

// ─────────────────────────────────────────────────────────────
//  BetterDiscord plugin class
// ─────────────────────────────────────────────────────────────
module.exports = class UXXDiscord {
  constructor() {
    this._styleEl = null;
    this._tokenStyleEl = null;
  }

  // Inject two <style> tags:
  //   1. Token vars  → :root { --uxx-* : <live values from package> }
  //   2. Discord map → .theme-dark { --background-primary: var(--uxx-...) }
  //   3. Components  → component selectors using --uxx-* vars
  async start() {
    try {
      // Prefer the local workspace install. Snap Discord/BD often cannot or should not
      // depend on esm.sh at renderer startup; keep the CDN as a fallback.
      const { getThemeCssVars, themePacks } = await this._loadUxxTokens();

      // Pick the active theme — extend this to read a BD setting if you want
      // a theme switcher. For now defaults to monokai.
      const themeName = this._getSavedTheme();
      const pack = themePacks[themeName] ?? themePacks.monokai;
      const cssVars = getThemeCssVars(pack);

      // Build :root { --uxx-* } block from live token values
      const tokenLines = Object.entries(cssVars)
        .map(([k, v]) => `  ${k}: ${v};`)
        .join('\n');

      const tokenCss = `:root {\n${tokenLines}\n}\n`;
      const discordMapCss = `.theme-dark, .theme-light {\n${DISCORD_VARS}\n}\n`;

      this._tokenStyleEl = this._injectStyle('uxx-tokens', tokenCss + discordMapCss);
      this._styleEl = this._injectStyle('uxx-components', COMPONENT_CSS);

      console.log(`[UXX Discord] Loaded theme: ${themeName}`);
    } catch (err) {
      console.error('[UXX Discord] Failed to load tokens from CDN:', err);
      // Fallback: inject component CSS only; Discord vars won't resolve
      // but the page won't crash
      this._styleEl = this._injectStyle('uxx-components', COMPONENT_CSS);
    }
  }

  stop() {
    this._tokenStyleEl?.remove();
    this._styleEl?.remove();
    this._tokenStyleEl = null;
    this._styleEl = null;
  }

  // ── BD Settings panel ──────────────────────────────────────
  getSettingsPanel() {
    const themes = ['monokai', 'night-owl', 'proxy-console'];
    const current = this._getSavedTheme();

    const wrap = document.createElement('div');
    wrap.style.cssText = 'padding: 16px; display: flex; flex-direction: column; gap: 12px;';

    const label = document.createElement('label');
    label.style.cssText = 'color: var(--text-normal); font-weight: 500;';
    label.textContent = 'UXX Theme';

    const select = document.createElement('select');
    select.style.cssText = [
      'background: var(--background-secondary)',
      'color: var(--text-normal)',
      'border: 1px solid var(--background-modifier-border)',
      'border-radius: 4px',
      'padding: 6px 10px',
      'font-size: 14px',
      'cursor: pointer',
    ].join(';');

    themes.forEach(t => {
      const opt = document.createElement('option');
      opt.value = t;
      opt.textContent = t;
      if (t === current) opt.selected = true;
      select.appendChild(opt);
    });

    select.addEventListener('change', () => {
      BdApi.Data.save('UXXDiscord', 'theme', select.value);
      this.stop();
      this.start();
    });

    wrap.append(label, select);
    return wrap;
  }

  // ── Helpers ────────────────────────────────────────────────
  async _loadUxxTokens() {
    const errors = [];
    for (const url of UXX_LOCAL_URLS) {
      try {
        return await import(url);
      } catch (err) {
        errors.push([url, err]);
      }
    }

    console.warn('[UXX Discord] Local @open-hax/uxx token imports failed; falling back to CDN:', errors);
    return await import(UXX_CDN);
  }

  _getSavedTheme() {
    return BdApi.Data.load('UXXDiscord', 'theme') ?? 'monokai';
  }

  _injectStyle(id, css) {
    const existing = document.getElementById(id);
    if (existing) existing.remove();
    const el = document.createElement('style');
    el.id = id;
    el.textContent = css;
    document.head.appendChild(el);
    return el;
  }
};
