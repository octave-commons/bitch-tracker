# BitchTracker BetterDiscord Plugin

Shadow-CLJS scaffold for a BetterDiscord plugin, with `pseudo/` kept as the reference-design archive.

## Rule zero

The distributable plugin must stay in BetterDiscord's CommonJS meta-factory shape:

```js
/**
 * @name BitchTracker
 * @author open-hax
 * @description Shadow-CLJS BetterDiscord plugin scaffold for Discord moderation/event tracking.
 * @version 0.0.1
 */

module.exports = meta => ({
  start: () => {},
  stop: () => {},
});
```

The ClojureScript entrypoint enforces that shape by assigning `module.exports` to `bitch-tracker.plugin/plugin-factory`, and `scripts/verify-bd-export.mjs` smoke-tests the final bundle.

## Source layout

- `src/bitch_tracker/plugin.cljs` — BetterDiscord plugin factory and lifecycle.
- `test/bitch_tracker/plugin_test.cljs` — `cljs.test` coverage, including `deftest ^:async` and `await`.
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
