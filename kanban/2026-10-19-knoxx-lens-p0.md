---
category: "tasks"
labels: "knoxx-lens,betterdiscord,knoxx,cljs,tests"
write-id: "1792467266606-0.kkcqp77b1y7h3juu0uq"
points: "5"
source: "kanban/2026-10-19-knoxx-lens-p0.md"
title: "KnoxxLens P0: ask a Knoxx agent about the open Discord channel"
priority: "P1"
status: "incoming"
uuid: "bitch-tracker-knoxx-lens-p0"
created_at: "2026-10-19T22:00:00Z"
---

# KnoxxLens P0: ask a Knoxx agent about the open Discord channel

## Outcome

A second BetterDiscord plugin, `KnoxxLens`, built from this repo. It opens a drawer that follows the open channel and lets the user ask a Knoxx agent about it, with explicit context selection modeled on AI coding tools. The plugin reaches Knoxx only through the existing bitch-tracker bot. The bot holds the credentials and streams answers back over the existing socket.io connection.

## Scope

- Context chips: the channel tail (last N loaded messages, toggle, `@last:N`) and pinned messages (message context menu → *Add to Knoxx context*). Token estimate and budget.
- Context messages use the shared OpenPlanner event builder, so they carry canonical `discord:<guild>:<channel>:<message>` ids into Knoxx `templateContext`.
- Bot broker messages: `lens:ask`, `lens:abort`, `lens:config:get` → `bot:lens:{config,accepted,token,event,done,error}`.
- Knoxx transport: `POST /api/knoxx/direct/start` with a bot-generated `runId`, and `/ws/stream?conversation_id=` opened before the turn starts.
- Fail-closed guards:
  - the model must be on the local-model allowlist (default `gemma4:e4b`);
  - the Knoxx URL must be private or loopback, because its WS stream is unauthenticated;
  - an API key must be present.
- Per-channel conversations (Knoxx conversation and session ids persisted in BdApi.Data). Stop and New controls.

## Non-goals

- Posting to Discord. The agent drafts only; a "post to channel" action is P2.
- OpenPlanner history expansion beyond the client's loaded messages (P1).
- `@user`, `@thread`, `@since`, `@search` chips (P1).
- Knoxx-side changes: a `discord_lens` contract, an OpenPlanner retrieval tool for `betterdiscord-openplanner` events, and reconciling the event-id scheme with Knoxx's `discord:message:<ch>:<msg>` (separate Knoxx cards).

## Acceptance criteria

- [x] `dist/KnoxxLens.plugin.js` builds and passes the BetterDiscord meta-factory export check.
- [x] BitchTracker plugin behavior unchanged; its interop helpers were extracted to `plugin.bd` and its export check still passes.
- [x] The bot refuses asks (no network calls) when the model is not local, the Knoxx URL is public, or the API key is missing.
- [x] The broker relays a full turn over real HTTP and WebSocket against a fake Knoxx: accepted → tokens → done, scoped to its run, with cleanup.
- [x] Lint is clean and the CLJS build has zero warnings. The new tests pass. The pre-existing `dedup-prunes-by-age-and-size` failure is unchanged.
- [ ] knoxx-social-local resolves `gemma4:e4b` to the local Ollama provider (`OLLAMA_BASE_URL`). This is an operator step on a live consumer.
- [ ] Live check: the plugin is installed in BetterDiscord, the bot is restarted with `KNOXX_API_KEY`, and one question about a test channel is answered by the local model.

## Verification

```sh
pnpm run lint
pnpm dlx shadow-cljs@3.4.11 compile test
pnpm run build:cljs && BETTERDISCORD_COPY=0 node scripts/write-bd-plugin.mjs && node scripts/verify-bd-export.mjs
```

---
2026-10-20 live steps.

Done:
- Bot restarted with KNOXX_API_KEY. OpenPlanner ingest resumed.
- Both plugins installed in BetterDiscord; BitchTracker hot-reloaded and reconnected.
- knoxx-social-local: gemma4:e4b contract overlaid to provider proxx-local (on-box Proxx, scoped proxy:use tenant key 8f625e62). Backend recreated: verify 33 PASS / 1 WARN / 1 FAIL. The FAIL is pre-existing Oct 9 drift in ussyverse_social_creative tools. OpenHax gateway READY.
- Proxx policy :provider-order/gemma4-e4b = llamacpp, ollama-host, ollama-lan; proxx-local restarted.

Blocked (Proxx):
- Cold Ollama load (~55s) exceeds the default 30s attempt timeout; ollama-host has no chat queue instance.
- A warm ollama-host ollama_chat attempt returned 200 but Proxx fell through to the next provider anyway.
- local.ts:29 uses the global OLLAMA_BASE_URL rather than the route base URL.

No live Knoxx turn has completed.

---