(ns tunarr.scheduler.http.api.bumpers-test
  (:require [clojure.test :refer [deftest is testing]]
            [tunarr.scheduler.http.api.bumpers :as sut]))

;; grout-channel-tag must produce exactly what Pseudovision's
;; pseudovision.media.grout-source/channel-tag derives from the PV channel
;; row's :name column — which channel sync sets from this same channel-spec's
;; :name (tunarr.scheduler.channels.sync/channel-spec->pseudovision). Using
;; the raw config-key slug instead (e.g. "enigma") would silently never match
;; PV's query ("enigma-tv"), orphaning every uploaded bumper.

(deftest grout-channel-tag-kebab-cases-display-name
  (testing "multi-word display name, matching PV's own kebab-casing"
    (is (= "enigma-tv" (#'sut/grout-channel-tag {:name "Enigma TV"}))))
  (testing "single-word display name"
    (is (= "spectrum" (#'sut/grout-channel-tag {:name "Spectrum"})))))

(deftest grout-channel-tag-nil-when-no-name
  (is (nil? (#'sut/grout-channel-tag {})))
  (is (nil? (#'sut/grout-channel-tag nil))))
