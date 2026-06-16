---
uuid: "bitch-tracker-openplanner-ingest-gap-closure"
title: "Close OpenPlanner ingest/backfill parity gaps"
status: done
priority: "P1"
labels: ["parity", "implementation", "betterdiscord", "cljs", "tests"]
created_at: "2026-06-01T00:00:00Z"
source: "kanban/2026-06-01-openplanner-ingest-gap-closure.md"
points: 5
category: "tasks"
---
# Close OpenPlanner ingest/backfill parity gaps

## Goal

Bring the CLJS BetterDiscord plugin to intended parity with the OpenPlanner event-ingest and backfill pseudo implementations.

## Known candidate gaps

- [x] Port or intentionally drop pseudo `_addUserNote` behavior.
- [x] Add bot REST retry handling for `429` retry-after and transient `5xx` responses, or document why not needed.
- [x] Redact bot tokens from failed bot-send response logs.
- [x] Decide whether `.env` file fallback from pseudo should exist in CLJS or remain settings/env-only.
- [x] Strengthen semantic scan parity, including finding the original message/channel after embeddings appear.
- [x] Add tests for protected-channel bot-only send behavior without leaking secrets.
- [x] Add tests for moderation watchlist loading and reaction labels.

## Acceptance criteria

- [x] `pnpm test` passes.
- [x] The parity ledger marks OpenPlanner ingest/backfill core as `present` or documents any intentional drops.
- [x] No secrets or bot tokens are logged in tests or runtime error messages.
- [x] BetterDiscord export verification still passes.

---
Started implementation: added token-redacted bot send failure logging, retry-after parsing visibility, and Discord user note tagging when the moderation threshold is reached.

Continued implementation: extracted safe async bot-send retry helper, added 429/5xx retry tests, added explicit quality reaction test, and strengthened pending semantic scan to recover channel/message and run delayed similarity searches after embeddings appear.

Finished scoped gap closure: added moderation watchTerms and flush-failure queue preservation tests, ported pseudo's multiple MessageActions send signatures, and decided `.env` file fallback plus named slur helper code are intentional drops in favor of explicit settings/config.
---
