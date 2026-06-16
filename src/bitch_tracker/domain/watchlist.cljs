(ns bitch-tracker.domain.watchlist
  "Pure functions for watch-list matching and regex compilation.
  No I/O.")

(defn compile-entry
  "Compiles a {:label s :pattern s} into {:label s :pattern regex}."
  [{:keys [label pattern]}]
  {:label   label
   :pattern (js/RegExp. pattern "i")})

(defn moderation-hits
  "Returns the seq of watch entries whose pattern matches text."
  [watch-entries text]
  (filter (fn [{:keys [pattern]}] (.test pattern text)) watch-entries))

(defn any-hit?
  "Returns true when any watch entry matches text."
  [watch-entries text]
  (boolean (seq (moderation-hits watch-entries text))))
