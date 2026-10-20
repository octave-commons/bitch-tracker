(ns bitch-tracker.law.lens
  "Admissibility checks for KnoxxLens turns. Validators only; no I/O.
  Each check returns a vector of human-readable problems; empty means admissible."
  (:require [clojure.string :as str]))

(def max-message-chars
  "Upper bound for the user's question text."
  4000)

(def max-context-chars
  "Hard upper bound for rendered context text accepted from a plugin."
  64000)

(def max-context-items
  "Upper bound for context items in one ask."
  16)

(defn- ipv4-octets [host]
  (when-let [m (re-matches #"(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})" host)]
    (mapv #(js/parseInt % 10) (rest m))))

(defn private-host?
  "True for loopback, RFC 1918, CGNAT/tailnet (100.64/10) and Docker host aliases."
  [host]
  (let [h (str/lower-case (str/replace (str host) #"^\[|\]$" ""))]
    (or (contains? #{"localhost" "::1" "host.docker.internal"} h)
        (when-let [[a b] (ipv4-octets h)]
          (or (= a 127)
              (= a 10)
              (and (= a 172) (<= 16 b 31))
              (and (= a 192) (= b 168))
              (and (= a 100) (<= 64 b 127)))))))

(defn private-base-url?
  "True when url parses and points at a private host. Knoxx's /ws/stream is
  unauthenticated, so the bot must never reach Knoxx over a public hostname."
  [url]
  (try
    (private-host? (.-hostname (js/URL. (str url))))
    (catch :default _ false)))

(defn local-model?
  "True when model is a non-blank member of the local-model allowlist."
  [allowlist model]
  (and (not (str/blank? model)) (contains? (set allowlist) model)))

(defn config-problems
  "Checks the bot's KnoxxLens config. Fails closed: any problem refuses every ask."
  [{:keys [knoxx-base-url knoxx-api-key lens-model lens-local-models]}]
  (cond-> []
    (str/blank? knoxx-api-key) (conj "KNOXX_API_KEY is not set on the bot")
    (not (private-base-url? knoxx-base-url)) (conj (str "KNOXX_BASE_URL must be a private/loopback address, got " (pr-str knoxx-base-url)))
    (not (local-model? lens-local-models lens-model)) (conj (str "KNOXX_LENS_MODEL " (pr-str lens-model) " is not in KNOXX_LENS_LOCAL_MODELS"))))

(defn ask-problems
  "Checks one plugin ask: {:request-id :conversation-id :message :channel :items}."
  [{:keys [request-id conversation-id message channel items]}]
  (let [context-chars (reduce + 0 (map (comp count :text) items))]
    (cond-> []
      (str/blank? request-id) (conj "requestId is required")
      (str/blank? conversation-id) (conj "conversationId is required")
      (str/blank? message) (conj "message is empty")
      (> (count (str message)) max-message-chars) (conj (str "message exceeds " max-message-chars " characters"))
      (str/blank? (:id channel)) (conj "channel.id is required")
      (> (count items) max-context-items) (conj (str "more than " max-context-items " context items"))
      (> context-chars max-context-chars) (conj (str "context exceeds " max-context-chars " characters")))))
