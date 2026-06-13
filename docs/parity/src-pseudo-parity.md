# `src/` ↔ `pseudo/` feature parity ledger

Date: 2026-06-01

## Scope decision

`src/bitch_tracker/plugin.cljs` is scoped as the canonical ClojureScript source for the BitchTracker BetterDiscord plugin. Parity is therefore measured first against the OpenPlanner moderation ingest/backfill pseudo plugins:

- `pseudo/OpenPlannerEventIngest.plugin.js`
- `pseudo/OpenPlannerBitchBackfill.plugin.js`
- `pseudo/OpenPlannerModerationWatchlist.json`
- selected moderation-detector ideas from `pseudo/discord-slur-patterns.js`

The Discord install set also includes the UXX Discord skin plugin because the intended BetterDiscord setup is BitchTracker plus UXX npm-package styling:

- `pseudo/UXXDiscord.plugin.js` — active companion plugin that imports `@open-hax/uxx` package tokens and injects Discord CSS variables/component styles.

The following pseudo artifacts are not considered active BitchTracker/UXX parity requirements unless separate cards are opened:

- `pseudo/HideDisabledEmojis.plugin.js` — separate emoji-picker UX plugin.
- `pseudo/HideDisabledEmojis.config.json` — runtime config for the separate emoji plugin.
- `pseudo/OpenPlannerEventIngest.config.json` — runtime persisted queue/config data, not source behavior.

## Parity table

| Area | Pseudo source | `src/` status | Follow-up |
|---|---|---:|---|
| BetterDiscord lifecycle | `start`, `stop`, `getSettingsPanel` | present | Covered by `test/bitch_tracker/plugin_test.cljs`. |
| BetterDiscord meta factory export | BD plugin files | present | Verified by `scripts/verify-bd-export.mjs`. |
| Discord module loading | `_loadModules` | present | Keep smoke-tested through build/export. |
| Message create subscription | `_onMessageCreate` | present | Add dispatcher fixture tests if regressions appear. |
| Reaction add/remove subscription | `_onReactionAdd`, `_onReactionRemove` | present | Existing reaction builder test covers emitted labels only. |
| OpenPlanner event queue | `_queue`, `_seen`, `_persistQueue` | present | Existing message event builder test covers JSON shape. |
| `/v1/events` flush | `_flush` | present | Fetch-failure test verifies queue preservation for retry. |
| API key + endpoint settings | `_endpoint`, `_apiKey` | present | Uses BetterDiscord settings, process env, and defaults. |
| `.env` file fallback | `_env`, `_envFileValue` | intentional-drop | Avoid hidden local repo paths; use settings/process env instead. |
| Bot config loading | `_botConfig`, `bot.json` | present | Numeric IDs are preserved as strings via raw JSON field extraction. |
| Protected tracker/watch sends | `_shouldUseBotForChannel`, `_sendViaBotToken` | present | `src/` blocks user-token fallback, redacts token text, and retries protected Bot REST sends. |
| Bot REST retry-after handling | `_retryAfterMs` + send retries | present | `src/` uses `send-bot-request-with-retries!` for 429 retry-after, transient 5xx, and thrown fetch errors. |
| Bot token redaction | `_redactToken` | present | Added `redact-token` around failed response body logs. |
| Discord HTTP/fetch/message-action sends | send fallback trio | present | `src/` now tries the same three MessageActions signature shapes as pseudo. |
| Mention sanitization | `_sanitizeMentions` | present | Used for tracker/watch content. |
| Poodle/clown moderation labels | `_isBitchEmoji`, `_handleBitchReaction` | present | Renamed in `src/` to moderation/watch terminology. |
| Threshold watch alert | `_tagUserAsBitch`, `_sendToBitchWatch` | partial | `src/` sends watch alert and now attempts user note; wording is softened. |
| Discord user note tagging | `_addUserNote` | present | Added `add-user-note!` with `addNote`/`updateNote` lookup. |
| Known-watch user forwarding | `knownBitchUserIds` flow | present | Renamed to `known-label-user-ids`. |
| Moderation watchlist file | `OpenPlannerModerationWatchlist.json` | present | `src/` reads from plugin file candidates rather than hard-coded snap path; settings watchTerms test covers label application. |
| Named slur detector helpers | `discord-slur-patterns.js` | intentional-drop | Current design keeps detector policy external/configurable instead of baking the reference helper into source. |
| Quality reaction labels | `_qualityFromEmoji`, `_handleQualityReaction` | present | Explicit quality reaction test covers `quality:good`. |
| Semantic similarity query | `_querySemanticSimilar` | present | Query path and threshold match pseudo. |
| Pending semantic scan | `_runSemanticScan` | present | `src/` now resolves user/channel/message after embeddings appear and runs delayed similarity search. |
| Backfill runner | `OpenPlannerBitchBackfill` | present | Integrated as `runBackfill`. |
| Backfill diagnostics | verbose console logs | partial | `src/` is quieter; acceptable unless debugging live Discord failures. |
| Backfill hard-coded snap watchlist path | `_watchConfig` in backfill pseudo | intentional-drop | `src/` uses portable plugin-file candidates. |
| Backfill dry-run setting | `dryRun` | missing | Add only if needed for safer live testing. |
| HideDisabledEmojis plugin | `HideDisabledEmojis.plugin.js` | out-of-scope | Track as separate plugin/package if needed. |
| UXX Discord theme plugin | `UXXDiscord.plugin.js` | active companion | Installed as a separate BetterDiscord plugin; now prefers `/home/err/devel/orgs/open-hax/uxx/dist/tokens/src/index.js` before root node_modules/CDN fallback. |

## Current implementation TODOs

No in-scope OpenPlanner ingest/backfill parity gaps are known after this pass. UXX Discord is active as a companion plugin and should be maintained against the `@open-hax/uxx` package token API. Future work should come from live BetterDiscord failures or an explicit request to split out the remaining out-of-scope pseudo plugins.

## Done-state claim

The repository is at intended parity for the scoped OpenPlanner ingest/backfill subset, and the local BetterDiscord install includes the UXX package-token style companion plugin. It is not at full `pseudo/` directory parity because HideDisabledEmojis remains explicitly out of scope.
