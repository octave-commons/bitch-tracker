# BitchTracker BetterDiscord Plugin

Shadow-CLJS BetterDiscord plugin for OpenPlanner Discord ingestion, reaction labeling, semantic watch alerts, and review backfill. `pseudo/` is retained as the reference-design archive that this source integrates.

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
```

Copy that generated file into the BetterDiscord plugins directory.
