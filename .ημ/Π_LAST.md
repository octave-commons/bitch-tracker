# Π Last Handoff — bitch-tracker

**Tag:** `pi/fork-tax/20260616T130247Z/bitch-tracker-coderabbit-merged`  
**Branch:** `pi/fork-tax/20260529T022118Z-main-softreset-all-dirt-bitch-tracker`  
**Timestamp:** 2026-06-16T13:02:47Z  
**Parent commits:** `5c5981f` (CodeRabbit fixes), `dc943e4` (eta-mu-sol domain/shape/law refactor)  
**Merge commit:** pending

## What changed

Merged the eta-mu-sol architectural refactor (`domain` / `shape` / `bot` layers) with the CodeRabbit review fixes from PR #1.

- Resolved merge conflicts in `src/bitch_tracker/bot/config.cljs` and `src/bitch_tracker/plugin/socket_client.cljs`.
- Preserved CodeRabbit fixes: externalized guild/user IDs via config/env, `^:async` + `await` in `main.cljs`, flush in-flight guard in `openplanner.cljs`, timestamp NaN validation in `events.cljs`, `allowed_mentions` suppression in pseudo plugins, relative watchlist path, per-message labeler tracking, removed duplicate quality event, moderation namespace labels, esbuild output guard, no default API key fallback, JSONC trailing comma fix.
- Cleaned the refactor's stricter clj-kondo config to zero warnings across all `src/` and `test/`.
- Fixed Node verification crash by guarding `js/window` access in `plugin/socket_client.cljs`.
- Removed tracked `.config` symlink and kept it ignored.

## Verification

- `pnpm run lint:clj` — 0 errors, 0 warnings
- `pnpm run test:cljs` — 17 tests, 62 assertions, 0 failures
- `pnpm run build` — plugin + bot release builds, bundle, BD export verify all passed

## Left out of this Π

None. All working-tree changes are staged in the merge commit.
