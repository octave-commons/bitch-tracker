(ns bitch-tracker.shape.coerce-test
  "Tests for bitch-tracker.shape.coerce."
  (:require [bitch-tracker.shape.coerce :as coerce]
            [cljs.test :refer-macros [deftest is testing]]))

(deftest message-id-coerces-to-string
  (testing "message-id reads the id property as a string"
    (is (= "m1" (coerce/message-id #js {:id "m1"})))
    (is (= "" (coerce/message-id #js {})))))

(deftest channel-id-prefers-channel_id
  (testing "channel-id reads channel_id before channelId"
    (is (= "c1" (coerce/channel-id #js {:channel_id "c1" :channelId "c2"})))
    (is (= "c2" (coerce/channel-id #js {:channelId "c2"})))
    (is (= "" (coerce/channel-id #js {})))))

(deftest guild-id-resolves-from-payload-or-channel
  (testing "guild-id falls back from payload to channel object"
    (is (= "g1" (coerce/guild-id #js {:guild_id "g1"})))
    (is (= "g2" (coerce/guild-id #js {:guildId "g2"})))
    (is (= "g3" (coerce/guild-id #js {} #js {:guild_id "g3"})))
    (is (= "g4" (coerce/guild-id #js {} #js {:getGuildId (fn [] "g4")})))
    (is (= "" (coerce/guild-id #js {})))))

(deftest emoji-str-prefers-name
  (testing "emoji-str returns the emoji name or falls back to id"
    (is (= "🐩" (coerce/emoji-str #js {:name "🐩" :id "123"})))
    (is (= "123" (coerce/emoji-str #js {:id "123"})))
    (is (= "" (coerce/emoji-str #js {})))))

(deftest author-id-resolves-many-forms
  (testing "author-id handles string authors and nested id fields"
    (is (= "u1" (coerce/author-id #js {:author #js {:id "u1"}})))
    (is (= "u2" (coerce/author-id #js {:author "u2"})))
    (is (= "u3" (coerce/author-id #js {:authorId "u3"})))
    (is (= "u4" (coerce/author-id #js {:author_id "u4"})))
    (is (= "u5" (coerce/author-id #js {:userId "u5"})))
    (is (= "u6" (coerce/author-id #js {:user_id "u6"})))
    (is (= "" (coerce/author-id #js {})))))

(deftest attachment->map-coerces-shape
  (testing "attachment object is coerced to the Attachment schema shape"
    (let [attachment #js {:id "a1"
                          :filename "image.png"
                          :content_type "image/png"
                          :size 1024
                          :url "http://example.com/a1.png"
                          :proxy_url "http://proxy.example.com/a1.png"
                          :width 100
                          :height 200}
          result (coerce/attachment->map attachment)]
      (is (= "a1" (:id result)))
      (is (= "image.png" (:filename result)))
      (is (= "image/png" (:content_type result)))
      (is (= 1024 (:size result)))
      (is (= "http://example.com/a1.png" (:url result)))
      (is (= "http://proxy.example.com/a1.png" (:proxy_url result)))
      (is (= 100 (:width result)))
      (is (= 200 (:height result))))))

(deftest attachment->map-falls-back-to-alternate-keys
  (testing "attachment coercion uses alternate JS key names"
    (let [attachment #js {:id "a2" :name "doc.pdf" :contentType "application/pdf" :proxyURL "http://proxy/doc.pdf"}
          result (coerce/attachment->map attachment)]
      (is (= "doc.pdf" (:filename result)))
      (is (= "application/pdf" (:content_type result)))
      (is (= "http://proxy/doc.pdf" (:proxy_url result))))))

(deftest js-seq-coerces-iterables
  (testing "js-seq converts JS arrays and objects to Clojure seqs"
    (is (= [1 2 3] (vec (coerce/js-seq #js [1 2 3]))))
    (is (= ["a" "b"] (sort (vec (coerce/js-seq #js {:x "a" :y "b"})))))
    (is (= [] (coerce/js-seq nil)))))
