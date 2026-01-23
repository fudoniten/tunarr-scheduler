(ns tunarr.scheduler.channels-test
  (:require [clojure.test :refer [deftest is testing]]
            [tunarr.scheduler.channels :as ch]))

(deftest valid-channel-basic-test
  (testing "Basic channel with required fields is valid"
    (let [channel {::ch/channel-id :test-1
                   ::ch/channel-name "Test Channel"
                   ::ch/channel-number 100}]
      (is (ch/valid-channel? channel))
      (is (nil? (ch/explain-channel channel))))))

(deftest valid-channel-with-options-test
  (testing "Channel with optional fields is valid"
    (let [channel {::ch/channel-id :test-1
                   ::ch/channel-name "Test Channel"
                   ::ch/channel-number 100
                   ::ch/channel-description "A test channel"
                   ::ch/channel-logo-url "http://example.com/logo.png"
                   ::ch/streaming-mode :hls-segmenter
                   ::ch/preferred-audio-language "en"
                   ::ch/subtitle-mode :burned-in}]
      (is (ch/valid-channel? channel))
      (is (nil? (ch/explain-channel channel))))))

(deftest invalid-channel-missing-required-test
  (testing "Channel missing required fields is invalid"
    (let [channel {::ch/channel-id :test-1
                   ::ch/channel-name "Test Channel"}]
      (is (not (ch/valid-channel? channel)))
      (is (some? (ch/explain-channel channel))))))

(deftest channel-number-conversions-test
  (testing "Channel number to string conversion"
    (is (= "100" (ch/channel-number->string 100)))
    (is (= "100.5" (ch/channel-number->string "100.5"))))
  
  (testing "Channel number to int conversion"
    (is (= 100 (ch/channel-number->int 100)))
    (is (= 100 (ch/channel-number->int "100")))
    (is (= 100 (ch/channel-number->int "100.5")))))

(deftest watermark-config-test
  (testing "Channel with watermark config is valid"
    (let [channel {::ch/channel-id :test-1
                   ::ch/channel-name "Test Channel"
                   ::ch/channel-number 100
                   ::ch/watermark-config {::ch/watermark-enabled true
                                         ::ch/watermark-url "http://example.com/wm.png"
                                         ::ch/watermark-position :bottom-right
                                         ::ch/watermark-opacity 50
                                         ::ch/watermark-size 100}}]
      (is (ch/valid-channel? channel)))))

(deftest decimal-channel-number-test
  (testing "Decimal channel numbers are valid"
    (let [channel {::ch/channel-id :test-1
                   ::ch/channel-name "Test Channel"
                   ::ch/channel-number "100.5"}]
      (is (ch/valid-channel? channel)))))
