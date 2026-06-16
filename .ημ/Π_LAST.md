# Π Last Handoff — bitch-tracker

**Tag:** `pi/fork-tax/20260616T071202Z/bitch-tracker-plugin-bot-decompose`  
**Branch:** `pi/fork-tax/20260529T022118Z-main-softreset-all-dirt-bitch-tracker`  
**Timestamp:** 2026-06-16T07:12:02Z  
**Parent commit:** `8b6f56c`

## What changed

Refactored `src/bitch_tracker/plugin.cljs` and split responsibilities into:

- `src/bitch_tracker/plugin/` — BetterDiscord plugin modules
- `src/bitch_tracker/bot/` — Discord bot modules
- `src/bitch_tracker/shared/` — shared protocol/utilities
- Added `test/bitch_tracker/config_test.cljs`
- Updated build/bundle scripts, `shadow-cljs.edn`, and `package.json`

## Verification

- `pnpm run lint:clj` — 0 errors, 0 warnings (1 info)
- `pnpm run test:cljs` — 17 tests, 62 assertions, 0 failures
- `pnpm run build` — plugin + bot release builds, bundle, BD export verify all passed

## Left out of this Π

- `.#AGENTS.md` — Emacs lock file
- `.lsp/` — LSP runtime cache
- `breakdown-plugin-and-bot.jsonl` — session transcript artifact
- `eta-mu-session-2026-06-15T02-41-57-776Z_019ec928-3c0f-77eb-a950-9c629ad87fc6.html` — session artifact
