# Π Last Handoff — bitch-tracker

**Tag:** `pi/fork-tax/20260618T133300Z/bitch-tracker-simplification-pass`  
**Branch:** `pi/fork-tax/20260529T022118Z-main-softreset-all-dirt-bitch-tracker`  
**Timestamp:** 2026-06-18T13:33:00Z  
**Parent commit:** (previous Π)

## What changed

### Simplification pass: remove over-engineering, fix self-loop

**config.cljs**
- Renamed `SOCKET_PORT` → `BITCH_TRACKER_SOCKET_PORT` with JSON fallback
- Removed `slapper-of-bitches-role-id` config field (unused after role-based disconnect message removed)

**dedup.cljs**
- Simplified to pure in-memory cache (removed filesystem persistence)
- Removed size-based pruning; TTL-only eviction on `seen?` calls
- `persist!` and `load!` are now no-ops for compatibility

**socket.cljs**
- Added bot self-filter: `not= author-id bot-user-id` prevents the bot from processing its own forwarded events
- Removed `slapper-role-id` from plugin disconnect status messages
- Added watchlist entry count on startup

**plugin.cljs**
- Removed guild-id gate from message/reaction forwarding — all events flow through, bot-side handles filtering
- This eliminates the "must configure guildIds in plugin" requirement

**socket_client.cljs**
- Direct `require ["socket.io-client"]` instead of runtime resolution from `js/window`/`js/globalThis`
- Removes fragile page-global detection; works cleanly in both BD and Node verify contexts

**discord.cljs**
- Removed `slapper-role-id` from disconnect message template

## Verification

- `pnpm run lint:clj` — 0 errors, 0 warnings ✓

## Left out of this Π (untouched, not staged)

- `.error/` — generated error output
- `dedup-cache.json` — runtime cache file (no longer used by code)
- `check-channel.js`, `list-channels.js`, `test-live.js`, `test-no-selfblock.js`, `test-real-event.js`, `test-wait.js` — ad-hoc test/debug scripts
