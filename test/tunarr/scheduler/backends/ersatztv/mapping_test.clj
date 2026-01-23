(ns tunarr.scheduler.backends.ersatztv.mapping-test
  (:require [clojure.test :refer [deftest is testing]]
            [tunarr.scheduler.channels :as ch]
            [tunarr.scheduler.backends.ersatztv.mapping :as mapping]))

(deftest channel-spec->ersatztv-basic-test
  (testing "Basic channel conversion to ErsatzTV format"
    (let [channel-spec {::ch/channel-id :horror-night
                        ::ch/channel-name "Horror Night"
                        ::ch/channel-number 100}
          ersatz (mapping/channel-spec->ersatztv channel-spec)]
      (is (= "100" (:number ersatz)))
      (is (= "Horror Night" (:name ersatz)))
      (is (not (contains? ersatz :description))))))

(deftest channel-spec->ersatztv-with-options-test
  (testing "Channel with optional fields converts correctly"
    (let [channel-spec {::ch/channel-id :horror-night
                        ::ch/channel-name "Horror Night"
                        ::ch/channel-number 100
                        ::ch/channel-description "Spooky movies all night"
                        ::ch/channel-logo-url "http://example.com/horror.png"
                        ::ch/streaming-mode :hls-segmenter
                        ::ch/channel-group "Movies"
                        ::ch/preferred-audio-language "en"}
          ersatz (mapping/channel-spec->ersatztv channel-spec)]
      (is (= "100" (:number ersatz)))
      (is (= "Horror Night" (:name ersatz)))
      (is (= "Spooky movies all night" (:description ersatz)))
      (is (= "http://example.com/horror.png" (:logo ersatz)))
      (is (= "HLS Segmenter" (:streamingMode ersatz)))
      (is (= "Movies" (:group ersatz)))
      (is (= "en" (:preferredAudioLanguage ersatz))))))

(deftest channel-spec->ersatztv-decimal-number-test
  (testing "Decimal channel numbers are preserved"
    (let [channel-spec {::ch/channel-id :test
                        ::ch/channel-name "Test"
                        ::ch/channel-number "100.5"}
          ersatz (mapping/channel-spec->ersatztv channel-spec)]
      (is (= "100.5" (:number ersatz))))))

(deftest channel-spec->ersatztv-watermark-test
  (testing "Watermark config converts correctly"
    (let [channel-spec {::ch/channel-id :test
                        ::ch/channel-name "Test"
                        ::ch/channel-number 100
                        ::ch/watermark-config {::ch/watermark-enabled true
                                              ::ch/watermark-url "http://example.com/wm.png"
                                              ::ch/watermark-position :bottom-right
                                              ::ch/watermark-opacity 75
                                              ::ch/watermark-size 150}}
          ersatz (mapping/channel-spec->ersatztv channel-spec)]
      (is (true? (:watermarkEnabled ersatz)))
      (is (= "http://example.com/wm.png" (:watermarkPath ersatz)))
      (is (= "BottomRight" (:watermarkLocation ersatz)))
      (is (= 75 (:watermarkOpacity ersatz)))
      (is (= 150 (:watermarkSize ersatz))))))

(deftest ersatztv->channel-spec-basic-test
  (testing "Basic ErsatzTV channel converts to universal format"
    (let [ersatz-channel {:id 1
                          :name "Horror Night"
                          :number "100"}
          channel-spec (mapping/ersatztv->channel-spec ersatz-channel)]
      (is (= :ch-1 (::ch/channel-id channel-spec)))
      (is (= "Horror Night" (::ch/channel-name channel-spec)))
      (is (= 100 (::ch/channel-number channel-spec)))
      (is (= 1 (::mapping/ersatz-id channel-spec))))))

(deftest ersatztv->channel-spec-with-options-test
  (testing "ErsatzTV channel with options converts correctly"
    (let [ersatz-channel {:id 2
                          :name "Comedy Central"
                          :number "200"
                          :description "Laugh all day"
                          :logo "http://example.com/comedy.png"
                          :streamingMode "HLS Segmenter"
                          :group "Entertainment"
                          :preferredAudioLanguage "en"}
          channel-spec (mapping/ersatztv->channel-spec ersatz-channel)]
      (is (= :ch-2 (::ch/channel-id channel-spec)))
      (is (= "Comedy Central" (::ch/channel-name channel-spec)))
      (is (= 200 (::ch/channel-number channel-spec)))
      (is (= "Laugh all day" (::ch/channel-description channel-spec)))
      (is (= "http://example.com/comedy.png" (::ch/channel-logo-url channel-spec)))
      (is (= :hls-segmenter (::ch/streaming-mode channel-spec)))
      (is (= "Entertainment" (::ch/channel-group channel-spec)))
      (is (= "en" (::ch/preferred-audio-language channel-spec))))))

(deftest ersatztv->channel-spec-decimal-number-test
  (testing "Decimal channel numbers are preserved"
    (let [ersatz-channel {:id 3
                          :name "Test"
                          :number "100.5"}
          channel-spec (mapping/ersatztv->channel-spec ersatz-channel)]
      (is (= "100.5" (::ch/channel-number channel-spec))))))

(deftest ersatztv->channel-spec-watermark-test
  (testing "Watermark config converts from ErsatzTV format"
    (let [ersatz-channel {:id 4
                          :name "Test"
                          :number "100"
                          :watermarkEnabled true
                          :watermarkPath "http://example.com/wm.png"
                          :watermarkLocation "TopLeft"
                          :watermarkOpacity 80
                          :watermarkSize 120}
          channel-spec (mapping/ersatztv->channel-spec ersatz-channel)
          wm-config (::ch/watermark-config channel-spec)]
      (is (true? (::ch/watermark-enabled wm-config)))
      (is (= "http://example.com/wm.png" (::ch/watermark-url wm-config)))
      (is (= :top-left (::ch/watermark-position wm-config)))
      (is (= 80 (::ch/watermark-opacity wm-config)))
      (is (= 120 (::ch/watermark-size wm-config))))))

(deftest round-trip-conversion-test
  (testing "Round-trip conversion preserves data"
    (let [original-spec {::ch/channel-id :test
                         ::ch/channel-name "Test Channel"
                         ::ch/channel-number 100
                         ::ch/channel-description "Test description"
                         ::ch/streaming-mode :hls-segmenter
                         ::ch/channel-group "Test Group"}
          ersatz (mapping/channel-spec->ersatztv original-spec)
          ;; Add an ID since ErsatzTV would assign one
          ersatz-with-id (assoc ersatz :id 999)
          round-trip (mapping/ersatztv->channel-spec ersatz-with-id)]
      ;; Check that key fields are preserved
      (is (= "Test Channel" (::ch/channel-name round-trip)))
      (is (= 100 (::ch/channel-number round-trip)))
      (is (= "Test description" (::ch/channel-description round-trip)))
      (is (= :hls-segmenter (::ch/streaming-mode round-trip)))
      (is (= "Test Group" (::ch/channel-group round-trip))))))

(deftest validate-ersatztv-channel-test
  (testing "Valid ErsatzTV channel passes validation"
    (let [channel {:id 1 :name "Test" :number "100"}
          result (mapping/validate-ersatztv-channel channel)]
      (is (:valid? result))
      (is (not (contains? result :errors)))))
  
  (testing "Invalid ErsatzTV channel fails validation"
    (let [channel {:name "Test"}
          result (mapping/validate-ersatztv-channel channel)]
      (is (not (:valid? result)))
      (is (seq (:errors result)))
      (is (some #(re-find #"id" %) (:errors result)))
      (is (some #(re-find #"number" %) (:errors result))))))

(deftest streaming-mode-mappings-test
  (testing "All streaming modes have bidirectional mappings"
    (doseq [[universal ersatz-str] mapping/streaming-mode->ersatz]
      (is (= universal (get mapping/ersatz->streaming-mode ersatz-str))
          (str "Missing reverse mapping for " ersatz-str)))))

(deftest watermark-position-mappings-test
  (testing "All watermark positions have bidirectional mappings"
    (doseq [[universal ersatz-str] mapping/watermark-position->ersatz]
      (is (= universal (get mapping/ersatz->watermark-position ersatz-str))
          (str "Missing reverse mapping for " ersatz-str)))))
