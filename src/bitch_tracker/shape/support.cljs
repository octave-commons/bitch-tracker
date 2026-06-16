(ns bitch-tracker.shape.support
  "Structure-only helpers: JS interop accessors, coercions, and type predicates.
  All functions are pure and domain-agnostic.")

(defn jget
  "Reads nested keys from a JS object. Returns nil when any key is absent."
  [obj & ks]
  (reduce (fn [o k] (when o (aget o k))) obj ks))

(defn jcall
  "Calls method-name on obj with args, returning nil when the method is absent."
  [obj method-name & args]
  (when-let [f (aget obj method-name)]
    (.apply f obj (into-array args))))

(defn present-string?
  "Returns true when v is a non-empty string."
  [v]
  (and (string? v) (pos? (.-length v))))

(defn js-seq
  "Converts a JS iterable (Array, Map.entries, Set, etc.) to a seq.
  Returns [] when value is nil or not iterable."
  [value]
  (cond
    (nil? value)             []
    (array? value)           (array-seq value)
    (aget value "toArray")   (array-seq (.call (aget value "toArray") value))
    (aget value "values")    (array-seq (js/Array.from (.call (aget value "values") value)))
    :else                    (array-seq (js/Object.values value))))

(defn now-iso
  "Returns the current UTC time as an ISO 8601 string."
  []
  (.toISOString (js/Date.)))

(defn str-or-empty
  "Returns (str v) or empty string when v is nil."
  [v]
  (str (or v "")))
