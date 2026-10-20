# BitchTracker BetterDiscord Plugin

This repo ships two BetterDiscord plugins that share one bot broker:

- **BitchTracker** — OpenPlanner ingestion, reaction labels, moderation watch (below).
- **KnoxxLens** — a drawer for asking a Knoxx agent about the open channel, with explicit context chips. See [KnoxxLens](#knoxxlens).

Shadow-CLJS BetterDiscord plugin for OpenPlanner Discord ingestion, reaction labeling, semantic watch alerts, and review backfill. `pseudo/` is retained as the reference-design archive that this source integrates. Current source-vs-pseudo parity status is tracked in `docs/parity/src-pseudo-parity.md`.

## Rule zero

The distributable plugin must stay in BetterDiscord's CommonJS meta-factory shape:

```js
/**
 * @name BitchTracker
 * @author open-hax
 * @description Shadow-CLJS BetterDiscord plugin for OpenPlanner Discord ingestion, reaction labeling, and review backfill.
 * @version 0.0.1
 */

module.exports = meta => ({
  start: () => {},
  stop: () => {},
  getSettingsPanel: () => HTMLElement,
  runBackfill: () => Promise,
  flush: () => Promise,
});
```

The ClojureScript entrypoint enforces that shape by assigning `module.exports` to `bitch-tracker.plugin/plugin-factory`, and `scripts/verify-bd-export.mjs` smoke-tests the final bundle.

## Source layout

- `src/bitch_tracker/plugin.cljs` — BetterDiscord plugin factory, Discord dispatcher subscriptions, OpenPlanner event queue/flush, reaction labels, semantic scan, bot-token protected sends, settings UI, and backfill runner.
- `test/bitch_tracker/plugin_test.cljs` — `cljs.test` coverage for the factory lifecycle and OpenPlanner event builders, including `deftest ^:async` and `await`.
- `shadow-cljs.edn` — plugin and Node test builds.
- `scripts/write-bd-plugin.mjs` — prepends BetterDiscord metadata to the compiled bundle.
- `scripts/verify-bd-export.mjs` — validates the final `module.exports = meta => plugin` contract.
- `dist/BitchTracker.plugin.js` — generated BetterDiscord plugin file.

## Commands

```sh
pnpm run lint
pnpm test
pnpm build
```

Build output:

```text
dist/BitchTracker.plugin.js
dist/KnoxxLens.plugin.js
```

Copy that generated file into the BetterDiscord plugins directory.

## KnoxxLens

A right-side drawer (toggle tab, or `Ctrl+Shift+K`) that follows the channel you have open and lets you ask a Knoxx agent about it.

```
KnoxxLens plugin ──socket.io :7878──▶ bitch-tracker bot ──HTTP──▶ Knoxx /api/knoxx/direct/start
      ▲  lens:ask / lens:abort                │ ◀──WS── /ws/stream?conversation_id=…
      └──── bot:lens:{accepted,token,event,done,error} ◀┘
```

Context selection:

- **Channel tail chip** — the last N loaded messages (default 50). Click to toggle. Type `@last:N` in the composer to resize it (`@last:0` turns it off).
- **Pinned chips** — right-click any message → *Add to Knoxx context*. Pinned messages always go first. Click a chip to remove it.
- The budget line estimates tokens (~4 chars/token) and reports any older tail messages it had to drop.

Each context message is projected through the same `shared.events/message-to-event` builder BitchTracker uses for ingestion, so it carries its canonical OpenPlanner event id (`discord:<guild>:<channel>:<message>`). The ids travel to Knoxx in `templateContext.discord.openplanner_event_ids`. The text travels as `contentParts` inside `<discord_context>` blocks, which the system prompt marks as untrusted third-party data.

Guards. The bot fails closed and refuses every ask unless all of these hold:

- `KNOXX_API_KEY` is set.
- `KNOXX_BASE_URL` is a loopback, RFC 1918 or tailnet address. Knoxx's `/ws/stream` is unauthenticated, so a public hostname is refused.
- `KNOXX_LENS_MODEL` is in `KNOXX_LENS_LOCAL_MODELS`, so Discord text only goes to local models.

The agent spec also denies Knoxx's Discord send/read tools and `websearch`. Knoxx only honours request tool policies when there is no auth context, so the API-key actor's role remains the authoritative guard.

Bot environment (`ecosystem.config.js`):

| Variable | Default |
|---|---|
| `KNOXX_BASE_URL` | `http://127.0.0.1:18881` (knoxx-social-local) |
| `KNOXX_API_KEY` | — (required; sourced from the shell, never committed) |
| `KNOXX_LENS_MODEL` | `gemma4:e4b` |
| `KNOXX_LENS_LOCAL_MODELS` | `gemma4:e4b,gemma4:latest` |
| `KNOXX_LENS_TURN_TIMEOUT_MS` | `300000` |

Model routing:

- knoxx-social-local's `gemma4:e4b` model contract is overlaid to provider `proxx-local`.
- That provider is the on-box Proxx at `host.docker.internal:8789`, using a `proxy:use` tenant key. It does not go through the deployment's remote Proxx.
- Proxx routes `gemma4:e4b` by its policy order: `llamacpp` → `ollama-host` → `ollama-lan`.

Two Proxx issues currently block that last hop:

- A cold Ollama load (~55 s) exceeds Proxx's default 30 s attempt timeout for `ollama-host`.
- A warm `ollama-host` 200 is still discarded.

See the KnoxxLens card for status.

Source:

- `src/bitch_tracker/lens.cljs` (plugin), `lens/panel.cljs` (DOM), `lens/discord.cljs` (stores, context menu).
- `bot/lens.cljs` (broker), `bot/knoxx.cljs` (transport).
- `domain/lens_{context,session,stream,turn}.cljs` (pure).
- `law/lens.cljs` (guards).

Tests: `test/bitch_tracker/lens_test.cljs` and `lens_wire_test.cljs`. The latter runs the broker against an in-process fake Knoxx over real HTTP and WebSocket.

`pnpm build` installs both plugin files into the BetterDiscord plugins directory. Set `BETTERDISCORD_COPY=0` to build without installing.
