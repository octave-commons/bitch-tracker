---
uuid: "bitch-tracker-src-pseudo-parity-audit"
title: "Audit src/pseudo feature parity"
status: done
priority: P1
labels: ["parity", "audit", "betterdiscord", "cljs"]
created_at: "2026-06-01T00:00:00Z"
source: "kanban/2026-06-01-src-pseudo-parity-audit.md"
points: 2
category: tasks
---
# Audit src/pseudo feature parity

## Goal

Produce a concrete parity ledger comparing `src/bitch_tracker/plugin.cljs` against the reference artifacts in `pseudo/`.

## Context

A first pass found that `src/` is close for the OpenPlanner ingest/backfill path, but not at full `pseudo/` parity.

## Scope

- Compare `src/bitch_tracker/plugin.cljs` with:
  - `pseudo/OpenPlannerEventIngest.plugin.js`
  - `pseudo/OpenPlannerBitchBackfill.plugin.js`
  - `pseudo/OpenPlannerModerationWatchlist.json`
  - `pseudo/discord-slur-patterns.js`
- Explicitly classify these as in-scope or out-of-scope:
  - `pseudo/HideDisabledEmojis.plugin.js`
  - `pseudo/UXXDiscord.plugin.js`
  - runtime state fixtures/config dumps

## Acceptance criteria

- [x] A checked-in parity table exists.
- [x] Each pseudo feature is marked `present`, `partial`, `missing`, or `intentional-drop`.
- [x] Each missing/partial in-scope feature has a follow-up task or is folded into an implementation task.
- [x] Test coverage gaps are listed alongside feature gaps.
