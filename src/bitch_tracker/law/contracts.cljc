(ns bitch-tracker.law.contracts
  "Boundary contract validators built on bitch-tracker.law.schemas.

  Each validator throws an ex-info with a human-readable explanation when the
  input violates its schema. These are the enforcement layer: they decide
  whether a value is admissible, but contain no I/O."
  (:require [malli.core :as m]
            [malli.error :as me]
            [bitch-tracker.law.schemas :as schemas]))

(defn- validate!
  "Throws when value does not conform to schema, including humanized errors."
  [schema value subject]
  (when-not (m/validate schema value)
    (throw (ex-info (str "Invalid " subject)
                    {:type :bitch-tracker.validation/error
                     :subject subject
                     :value value
                     :errors (me/humanize (m/explain schema value))}))))

(defn valid-open-planner-event!
  "Throws ex-info with humanized Malli errors when event is invalid."
  [event]
  (validate! schemas/OpenPlannerEvent event "OpenPlanner event"))

(defn valid-label-payload!
  "Throws ex-info with humanized Malli errors when payload is invalid."
  [payload]
  (validate! schemas/LabelPayload payload "label payload"))

(defn valid-bot-config!
  "Throws ex-info with humanized Malli errors when config is invalid."
  [config]
  (validate! schemas/BotConfig config "bot config"))

(defn valid-socket-message!
  "Throws ex-info with humanized Malli errors when message is invalid."
  [message]
  (validate! schemas/SocketMessage message "socket message"))
