(ns bitch-tracker.domain.label
  "Pure label-count state machine.

  State shape:
    {:counts   {user-id -> int}
     :messages {user-id -> #{message-id}}
     :labeled  #{message-id}}

  All functions are synchronous, pure, and return new state. No I/O.")

(defn empty-state
  "Returns an empty label state."
  []
  {:counts {}
   :messages {}
   :labeled #{}})

(defn add
  "Returns a new state with message-id counted once for user-id. Idempotent
  when the same user already has the message-id."
  [state user-id message-id]
  (let [user-messages (get-in state [:messages user-id] #{})]
    (if (contains? user-messages message-id)
      state
      (-> state
          (assoc-in [:messages user-id] (conj user-messages message-id))
          (update-in [:counts user-id] (fnil inc 0))
          (update :labeled conj message-id)))))

(defn remove-label
  "Returns a new state with message-id removed for user-id. Counts floor at 0."
  [state user-id message-id]
  (let [user-messages (get-in state [:messages user-id] #{})]
    (if-not (contains? user-messages message-id)
      state
      (let [next-messages (disj user-messages message-id)
            next-state (-> state
                           (assoc-in [:messages user-id] next-messages)
                           (update-in [:counts user-id] (fnil #(max 0 (dec %)) 0))
                           (update :labeled disj message-id))]
        (if (empty? next-messages)
          (update next-state :messages dissoc user-id)
          next-state)))))

(defn count-for
  "Returns the current label count for user-id."
  [state user-id]
  (get-in state [:counts user-id] 0))

(defn labeled?
  "True when message-id has been labeled by any user."
  [state message-id]
  (contains? (:labeled state) message-id))

(defn threshold-crossed?
  "True when the user's current count is strictly below threshold."
  [state user-id threshold]
  (< (count-for state user-id) threshold))
