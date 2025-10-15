(ns tunarr.scheduler.tts-test
  (:require [clojure.test :refer [deftest is testing]]
            [tunarr.scheduler.tts :as tts]))

(deftest create-client-default-test
  (let [client (tts/create-client {:provider "acme" :voice "alice"})]
    (is (= :generic (:type client)))
    (is (= "acme" (:provider client)))
    (is (= "alice" (:voice client)))))

(deftest create-client-mock-test
  (is (= {:type :mock}
         (tts/create-client {:provider :mock}))))

(deftest close-default-client-invokes-close-fn
  (let [closed? (atom false)
        client {:type :custom :close #(reset! closed? true)}]
    (tts/close! client)
    (is @closed?)))

(deftest synthesize-test
  (let [client {:type :custom :voice "default"}
        result (tts/synthesize client {:script "Hello" :voice "bob"})]
    (is (string? (:path result)))
    (is (re-find #"audio/" (:path result)))
    (is (= "bob" (:voice result)))))
