(ns bitch-tracker.domain.lens-session
  "Per-channel KnoxxLens conversation state for the plugin's drawer.
  No I/O. A conversation is {:conversation-id :session-id :turns [...] :running-request}."
  (:require [clojure.string :as str]))

(defn new-conversation
  "Returns an empty conversation bound to a fresh Knoxx conversation id."
  [conversation-id]
  {:conversation-id conversation-id
   :session-id nil
   :turns []
   :running-request nil})

(defn running?
  "True while a turn is awaiting completion."
  [conversation]
  (some? (:running-request conversation)))

(defn begin-turn
  "Appends the user's question and a pending assistant turn tagged with request-id."
  [conversation request-id question context-labels]
  (-> conversation
      (update :turns conj
              {:role :user :text question :context context-labels}
              {:role :assistant :request-id request-id :text "" :status :pending :events []})
      (assoc :running-request request-id)))

(defn- update-turn [conversation request-id f]
  (update conversation :turns
          (fn [turns] (mapv #(if (= request-id (:request-id %)) (f %) %) turns))))

(defn- settle [conversation request-id]
  (cond-> conversation
    (= request-id (:running-request conversation)) (assoc :running-request nil)))

(defn apply-reply
  "Applies a bot reply to the turn with request-id.
  kind is one of :accepted :token :event :done :error; data is a keyword-keyed map."
  [conversation kind request-id data]
  (case kind
    :accepted (cond-> (update-turn conversation request-id #(assoc % :status :streaming :model (:model data)))
                (not (str/blank? (:sessionId data))) (assoc :session-id (:sessionId data)))
    :token (update-turn conversation request-id #(-> % (assoc :status :streaming) (update :text str (:token data))))
    :event (update-turn conversation request-id #(update % :events conj (select-keys data [:type :tool])))
    :done (-> conversation
              (update-turn request-id (fn [turn]
                                        (assoc turn
                                               :status :done
                                               :text (if (> (count (str (:text data))) (count (:text turn)))
                                                       (str (:text data))
                                                       (:text turn)))))
              (settle request-id))
    :error (-> conversation
               (update-turn request-id #(assoc % :status :error :error (str (:error data))))
               (settle request-id))
    conversation))
