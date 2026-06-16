(ns bitch-tracker.shape.coerce-test
  "Tests for bitch-tracker.shape.coerce.

  All inputs are synthesised #js objects that mirror the shapes produced by
  the Discord client and the BD plugin API."
  (:require [cljs.test :refer [deftest is testing]]
            [bitch-tracker.shape.coerce :as coerce]))

;; ---------------------------------------------------------------------------
;; message-id
;; ---------------------------------------------------------------------------

(deftest message-id-extracts-id-field
  (testing "message-id extracts the id property from a message object"
    (is (= "123" (coerce/message-id #js {:id "123"})))))

(deftest message-id-returns-empty-string-for-nil
  (testing "message-id returns an empty string when message is nil"
    (is (= "" (coerce/message-id nil)))))

;; ---------------------------------------------------------------------------
;; channel-id
;; ---------------------------------------------------------------------------

(deftest channel-id-prefers-snake-case-key
  (testing "channel-id reads channel_id first"
    (is (= "ch1" (coerce/channel-id #js {:channel_id "ch1"})))))

(deftest channel-id-falls-back-to-camel-case
  (testing "channel-id falls back to channelId"
    (is (= "ch2" (coerce/channel-id #js {:channelId "ch2"})))))

(deftest channel-id-returns-empty-string-when-absent
  (testing "channel-id returns empty string when neither key is present"
    (is (= "" (coerce/channel-id #js {})))))

;; ---------------------------------------------------------------------------
;; guild-id
;; ---------------------------------------------------------------------------

(deftest guild-id-reads-from-payload
  (testing "guild-id extracts guild_id from payload"
    (is (= "g1" (coerce/guild-id #js {:guild_id "g1"})))))

(deftest guild-id-falls-back-to-channel-arg
  (testing "guild-id falls back to the channel object's guild_id"
    (is (= "g2" (coerce/guild-id #js {} #js {:guild_id "g2"})))))

(deftest guild-id-returns-empty-string-when-all-absent
  (testing "guild-id returns empty string when nothing resolves"
    (is (= "" (coerce/guild-id #js {} nil)))))

;; ---------------------------------------------------------------------------
;; emoji-str
;; ---------------------------------------------------------------------------

(deftest emoji-str-prefers-name
  (testing "emoji-str uses the emoji name when available"
    (is (= "thumbsup" (coerce/emoji-str #js {:name "thumbsup" :id "99"})))))

(deftest emoji-str-falls-back-to-id
  (testing "emoji-str falls back to the emoji id"
    (is (= "99" (coerce/emoji-str #js {:id "99"})))))

(deftest emoji-str-returns-empty-string-for-nil
  (testing "emoji-str returns empty string for nil"
    (is (= "" (coerce/emoji-str nil)))))

;; ---------------------------------------------------------------------------
;; author-id
;; ---------------------------------------------------------------------------

(deftest author-id-extracts-nested-author-id
  (testing "author-id traverses the nested author object"
    (is (= "u1" (coerce/author-id #js {:author #js {:id "u1"}})))))

(deftest author-id-handles-flat-string-author
  (testing "author-id handles a message where :author is itself the id string"
    (is (= "u2" (coerce/author-id #js {:author "u2"})))))

(deftest author-id-returns-empty-string-when-unresolvable
  (testing "author-id returns empty string when no id path resolves"
    (is (= "" (coerce/author-id #js {})))))

;; ---------------------------------------------------------------------------
;; attachment->map
;; ---------------------------------------------------------------------------

(deftest attachment->map-produces-clojure-map
  (testing "attachment->map converts a JS attachment to a Clojure map"
    (let [att #js {:id "a1" :filename "photo.png" :size 1024
                   :url "https://cdn.discord.com/a1" :content_type "image/png"}
          m   (coerce/attachment->map att)]
      (is (= "a1" (:id m)))
      (is (= "photo.png" (:filename m)))
      (is (= 1024 (:size m)))
      (is (= "image/png" (:content_type m))))))

(deftest attachment->map-handles-camel-case-content-type
  (testing "attachment->map accepts contentType as well as content_type"
    (let [m (coerce/attachment->map #js {:id "a2" :contentType "video/mp4"})]
      (is (= "video/mp4" (:content_type m))))))

;; ---------------------------------------------------------------------------
;; js-seq
;; ---------------------------------------------------------------------------

(deftest js-seq-converts-js-array
  (testing "js-seq converts a JS array to a seq"
    (is (= [1 2 3] (vec (coerce/js-seq #js [1 2 3]))))))

(deftest js-seq-returns-empty-for-nil
  (testing "js-seq returns [] for nil"
    (is (= [] (coerce/js-seq nil)))))

(deftest js-seq-converts-js-object-via-values
  (testing "js-seq falls back to Object.values for plain JS objects"
    ;; values of a single-key object is a one-element seq
    (let [result (vec (coerce/js-seq #js {:a 1}))]
      (is (= 1 (count result))))))
