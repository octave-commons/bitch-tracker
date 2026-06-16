# Π Handoff: chore/category-theory

## Snapshot
- Branch: chore/category-theory
- Verb: restructure bitch-tracker to eta-mu-sol architecture (pure layer only)
- Verification: clj-kondo --lint src test = 0 warnings; bb test = 63 tests / 162 assertions / 0 failures
- Manifest: see git tree for src/bitch_tracker/{domain,law,shape} and test/bitch_tracker/{domain,law,shape}

## Deliverables
- src/bitch_tracker/law/schemas.cljc
- src/bitch_tracker/law/contracts.cljc
- src/bitch_tracker/shape/coerce.cljs
- src/bitch_tracker/domain/label.cljs
- src/bitch_tracker/domain/watchlist.cljs
- src/bitch_tracker/domain/dedup.cljs
- test/bitch_tracker/domain/label_test.cljs
- test/bitch_tracker/domain/watchlist_test.cljs
- test/bitch_tracker/domain/dedup_test.cljs
- test/bitch_tracker/shape/coerce_test.cljs
- test/bitch_tracker/law/schemas_test.cljs
- test/bitch_tracker/law/contracts_test.cljs
- bb.edn

## Build support changed
- shadow-cljs.edn: added metosin/malli 0.16.4

## Concurrent / unowned dirt left untouched
- AGENTS.md modified with unrelated extern-boundary guidance (not staged)
- .lsp/ runtime directory
- breakdown-plugin-and-bot.jsonl
- eta-mu-session-2026-06-15T02-41-57-776Z_019ec928-3c0f-77eb-a950-9c629ad87fc6.html
