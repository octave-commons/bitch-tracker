(Π-state
  (repo "bitch-tracker")
  (branch "pi/fork-tax/20260529T022118Z-main-softreset-all-dirt-bitch-tracker")
  (tag "pi/fork-tax/20260616T071202Z/bitch-tracker-plugin-bot-decompose")
  (timestamp "2026-06-16T07:12:02Z")
  (parent-commit "8b6f56c")
  (verification
    (clj-kondo "0 errors, 0 warnings, 1 info")
    (shadow-cljs-test "17 tests, 62 assertions, 0 failures, 0 errors")
    (shadow-cljs-build "plugin+bot release builds completed, 0 warnings")
    (bd-verify "verified BetterDiscord meta factory export"))
  (blockers
    (runtime-dirt ".#AGENTS.md Emacs lock file")
    (runtime-dirt ".lsp/ LSP cache directory")
    (session-artifact "breakdown-plugin-and-bot.jsonl")
    (session-artifact "eta-mu-session-2026-06-15T02-41-57-776Z_019ec928-3c0f-77eb-a950-9c629ad87fc6.html")))
