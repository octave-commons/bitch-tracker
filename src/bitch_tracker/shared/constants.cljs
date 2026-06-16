(ns bitch-tracker.shared.constants)

(def plugin-name "BitchTracker")
(def plugin-version "0.0.1")
(def plugin-description
  "BetterDiscord moderation/event tracker for OpenPlanner Discord ingestion, reaction labeling, and review backfill.")

(def default-bot-url "http://127.0.0.1:7878")
(def default-openplanner-url "http://127.0.0.1:7777")
(def default-project "discord")
(def max-batch-size 25)
(def flush-every-ms 1500)
(def retry-every-ms 10000)
(def semantic-scan-every-ms 30000)
(def max-persisted-events 500)
(def backfill-days 7)
(def backfill-batch-size 100)
(def channel-delay-ms 1500)
(def message-delay-ms 1000)
(def tracker-channel-id "1503465577132462130")
(def watch-channel-id "1503466522666995892")
(def poodle-emoji "🐩")
(def clown-emoji "🤡")
(def label-threshold 3)

(def default-semantic-query-instruction
  "Represent the Discord message for semantic retrieval of similar moderation incidents: sexist, racist, transphobic, ableist, antisemitic, misgendering, harassment, or 'just joking' bigotry. Retrieve messages with similar abusive social behavior and intent, not merely exact wording.")

(def guild-ids
  #{"1228232798448390144"
    "1391832426048651334"
    "974519864045756446"
    "1128867683291627614"
    "244230771232079873"
    "1425557239808393418"})

(def known-label-user-ids
  #{"59259128266100736"
    "376762142910578692"
    "440099490364391435"
    "1441420406711124169"
    "281812122445283330"
    "853343486756388944"})
