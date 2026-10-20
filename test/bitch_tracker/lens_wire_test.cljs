(ns bitch-tracker.lens-wire-test
  "Exercises the KnoxxLens broker's real HTTP + WebSocket transport against an
  in-process fake Knoxx (node:http plus a minimal RFC 6455 server handshake)."
  (:require ["crypto" :as crypto]
            ["http" :as http]
            [bitch-tracker.bot.lens :as lens]
            [bitch-tracker.shape.protocol :as proto]
            [cljs.test :as t]))

(def ^:private ws-guid "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")

(defn- ws-frame
  "Encodes an unmasked server->client text frame (payloads < 64 KiB)."
  [text]
  (let [payload (js/Buffer.from text "utf8")
        n (.-length payload)
        header (if (< n 126)
                 (js/Buffer.from #js [0x81 n])
                 (js/Buffer.from #js [0x81 126 (bit-shift-right n 8) (bit-and n 0xff)]))]
    (js/Buffer.concat #js [header payload])))

(defn- send-envelope! [^js sock channel payload]
  (.write sock (ws-frame (js/JSON.stringify (clj->js {:channel channel :payload payload})))))

(defn- read-body [^js req]
  (js/Promise.
   (fn [resolve-fn _]
     (let [chunks #js []]
       (.on req "data" #(.push chunks %))
       (.on req "end" #(resolve-fn (str (js/Buffer.concat chunks))))))))

(defn- start-fake-knoxx!
  "Starts a fake Knoxx on an ephemeral port. Resolves to {:server :port :seen atom}."
  []
  (let [seen (atom {:bodies [] :api-keys [] :aborts 0})
        clients (atom [])
        server (http/createServer
                (fn [^js req ^js res]
                  (-> (read-body req)
                      (.then
                       (fn [raw]
                         (swap! seen update :api-keys conj (aget (.-headers req) "x-api-key"))
                         (case (.-url req)
                           "/api/knoxx/direct/start"
                           (let [body (js->clj (js/JSON.parse raw) :keywordize-keys true)
                                 run-id (:runId body)
                                 cid (:conversationId body)]
                             (swap! seen update :bodies conj body)
                             (.writeHead res 202 #js {"content-type" "application/json"})
                             (.end res (js/JSON.stringify #js {:ok true :run_id run-id :session_id "s-fake" :model (:model body)}))
                             (js/setTimeout
                              (fn []
                                (doseq [{:keys [sock conversation-id]} @clients
                                        :when (= cid conversation-id)]
                                  (send-envelope! sock "stats" {:cpu 1})
                                  (send-envelope! sock "tokens" {:run_id run-id :conversation_id cid :kind "assistant_message" :token "Hel"})
                                  (send-envelope! sock "tokens" {:run_id run-id :conversation_id cid :kind "assistant_message" :token "lo"})
                                  (send-envelope! sock "events" {:run_id run-id :conversation_id cid :type "run_completed"})))
                              20))

                           "/api/knoxx/abort"
                           (do (swap! seen update :aborts inc)
                               (.writeHead res 200 #js {"content-type" "application/json"})
                               (.end res "{\"ok\":true}"))

                           (do (.writeHead res 404) (.end res))))))))]
    (.on server "upgrade"
         (fn [^js req ^js sock _head]
           (let [accept (-> (crypto/createHash "sha1")
                            (.update (str (aget (.-headers req) "sec-websocket-key") ws-guid))
                            (.digest "base64"))
                 url (js/URL. (str "http://localhost" (.-url req)))]
             (.write sock (str "HTTP/1.1 101 Switching Protocols\r\n"
                               "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                               "Sec-WebSocket-Accept: " accept "\r\n\r\n"))
             (.on sock "error" (fn [_] nil))
             (swap! clients conj {:sock sock :conversation-id (.get (.-searchParams url) "conversation_id")}))))
    (js/Promise.
     (fn [resolve-fn _]
       (.listen server 0 "127.0.0.1"
                #(resolve-fn {:server server :port (.-port (.address server)) :seen seen :clients clients}))))))

(defn ^:async wait-until
  "Polls pred every 10ms until truthy or timeout-ms elapses; resolves to pred's last value."
  [pred timeout-ms]
  (let [deadline (+ (js/Date.now) timeout-ms)]
    (loop []
      (let [v (pred)]
        (if (or v (> (js/Date.now) deadline))
          v
          (do (await (js/Promise. (fn [r _] (js/setTimeout r 10))))
              (recur)))))))

;; Tests the full broker path: WS opened before start, scoped relay, done text, cleanup.
(t/deftest ^:async broker-relays-a-knoxx-turn-over-real-transport
  (let [{:keys [^js server port seen clients]} (await (start-fake-knoxx!))
        config {:knoxx-base-url (str "http://127.0.0.1:" port)
                :knoxx-api-key "test-key"
                :lens-model "gemma4:e4b"
                :lens-local-models #{"gemma4:e4b"}
                :lens-turn-timeout-ms 5000}
        emitted (atom [])
        socket #js {:id "sock-1"
                    :emit (fn [event-name data] (swap! emitted conj [event-name (js->clj data :keywordize-keys true)]))}
        runs (lens/make-state)
        ask #js {:requestId "q1" :conversationId "conv-1" :message "what happened?"
                 :channel #js {:id "c1" :name "general" :guild-id "g1"}
                 :items #js [#js {:kind "channel-tail" :label "#general · last 1"
                                  :text "<discord_context>…</discord_context>"
                                  :event-ids #js ["discord:g1:c1:m1"]}]}]
    (try
      (await (lens/handle-ask! runs config socket ask))
      (await (wait-until #(some (fn [[e]] (= e proto/lens-done-to-plugin)) @emitted) 3000))
      (let [events (map first @emitted)
            by-event (into {} (map (fn [[e d]] [e d])) @emitted)
            body (first (:bodies @seen))]
        (t/is (= [proto/lens-accepted-to-plugin proto/lens-token-to-plugin proto/lens-token-to-plugin proto/lens-done-to-plugin]
                 (vec events)))
        (t/is (= "s-fake" (:sessionId (get by-event proto/lens-accepted-to-plugin))))
        (t/is (= "Hello" (:text (get by-event proto/lens-done-to-plugin))))
        (t/is (every? #(= "q1" (:requestId (second %))) @emitted) "every reply is tagged with the request id")
        (t/is (= ["test-key"] (:api-keys @seen)) "the bot authenticates with X-API-Key")
        (t/is (= 1 (count @clients)) "the stream is opened once, scoped to the conversation")
        (t/is (= "gemma4:e4b" (:model body) (get-in body [:agentSpec :model])))
        (t/is (= "conv-1" (:conversationId body)))
        (t/is (= ["discord:g1:c1:m1"] (get-in body [:templateContext :discord :openplanner_event_ids])))
        (t/is (= [{:type "text" :text "<discord_context>…</discord_context>"}] (:contentParts body)))
        (t/is (empty? @runs) "the run is forgotten after completion"))
      (finally
        (doseq [{:keys [^js sock]} @clients] (.destroy sock))
        (.closeAllConnections server)
        (.close server)))))
