(ns bitch-tracker.shared.constants)

(def plugin-name
  "Plugin display name."
  "BitchTracker")

(def plugin-version
  "Plugin semantic version."
  "0.0.1")

(def plugin-description
  "Human-readable plugin description."
  "BetterDiscord moderation/event tracker for OpenPlanner Discord ingestion, reaction labeling, and review backfill.")

(def default-bot-url
  "Default URL for the local bot HTTP API."
  "http://127.0.0.1:7878")

(def default-openplanner-url
  "Default URL for the local OpenPlanner HTTP API."
  "http://127.0.0.1:7777")

(def default-project
  "Default OpenPlanner project slug."
  "discord")

(def max-batch-size
  "Maximum number of events to accumulate before flushing."
  25)

(def flush-every-ms
  "Maximum milliseconds to wait before flushing pending events."
  1500)

(def retry-every-ms
  "Milliseconds between ingestion retry attempts."
  10000)

(def semantic-scan-every-ms
  "Milliseconds between semantic similarity scans."
  30000)

(def max-persisted-events
  "Maximum number of events to persist locally."
  500)

(def backfill-days
  "Number of days to look back when backfilling messages."
  7)

(def backfill-batch-size
  "Number of messages to fetch per backfill batch."
  100)

(def channel-delay-ms
  "Milliseconds to wait between channel backfill requests."
  1500)

(def message-delay-ms
  "Milliseconds to wait between message backfill requests."
  1000)

(def tracker-channel-id
  "Discord channel id used for tracker output."
  "1503465577132462130")

(def watch-channel-id
  "Discord channel id monitored for moderation watch."
  "1503466522666995892")

(def poodle-emoji
  "Emoji used to flag messages for moderation review."
  "🐩")

(def clown-emoji
  "Emoji used to mark messages as clown behavior."
  "🤡")

(def label-threshold
  "Minimum count required to apply a moderation label."
  3)

(def default-semantic-query-instruction
  "Default prompt for embedding messages during semantic similarity scans."
  "Represent the Discord message for semantic retrieval of similar moderation incidents: sexist, racist, transphobic, ableist, antisemitic, misgendering, harassment, or 'just joking' bigotry. Retrieve messages with similar abusive social behavior and intent, not merely exact wording.")
