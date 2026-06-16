---
uuid: "bitch-tracker-pseudo-plugin-scope-decision"
title: "Decide scope for non-ingest pseudo plugins"
status: done
priority: P2
labels: ["parity", "scope", "betterdiscord", "uxx"]
created_at: "2026-06-01T00:00:00Z"
source: "kanban/2026-06-01-pseudo-plugin-scope-decision.md"
points: 3
category: tasks
---
# Decide scope for non-ingest pseudo plugins

## Goal

Decide whether pseudo artifacts unrelated to the BitchTracker ingest/backfill plugin should be migrated, split out, archived, or explicitly ignored for parity.

## Final decision

`pseudo/UXXDiscord.plugin.js` is part of the active BetterDiscord setup as a companion plugin. It should use the `@open-hax/uxx` npm package token build for styles. `HideDisabledEmojis` remains out of scope.

## Artifacts under decision

- `pseudo/HideDisabledEmojis.plugin.js`
- `pseudo/HideDisabledEmojis.config.json`
- `pseudo/UXXDiscord.plugin.js`
- `pseudo/discord-slur-patterns.js`
- large runtime config/state dumps such as `pseudo/OpenPlannerEventIngest.config.json`

## Decision options

1. Treat `HideDisabledEmojis` as out-of-scope reference material.
2. Keep `UXXDiscord.plugin.js` active as a separate BetterDiscord plugin that imports UXX package tokens.
3. Port selected pieces into BitchTracker only when they directly support moderation ingest.

## Acceptance criteria

- [x] A scope decision is written in the parity ledger or README.
- [x] Non-ingest pseudo artifacts are either tracked by separate tasks or marked intentional-drop/out-of-scope.
- [x] The user-facing parity claim becomes unambiguous.
