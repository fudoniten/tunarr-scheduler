(ns tunarr.scheduler.scheduling.native-schedule-test
  "Tests for the Grid -> Pseudovision native schedule/slot translator."
  (:require [clojure.test :refer [deftest testing is]]
            [tunarr.scheduler.scheduling.native-schedule :as sut]))

(defn- strip [id days start end content & {:keys [priority]}]
  (cond-> {:strip_id id :days days :start start :end end :content content}
    priority (assoc :priority priority)))

(defn- content [media-id & {:keys [strategy label]}]
  (cond-> {:media_id media-id}
    strategy (assoc :strategy strategy)
    label    (assoc :label label)))

(def channel-tag "channel:hua")

;; ---------------------------------------------------------------------------
;; content-sources
;; ---------------------------------------------------------------------------

(deftest content-sources-dedupes-series-and-category
  (testing "a series strip and a random strip each produce one distinct source"
    (let [grid {:strips [(strip "s1" "weekdays" "17:00" "17:30" (content "series:42" :strategy "sequential"))
                         (strip "s2" "weekends" "17:00" "18:00" (content "random:sitcom"))
                         (strip "s3" "weekdays" "20:00" "20:30" (content "series:42" :strategy "sequential"))]}
          sources (sut/content-sources grid channel-tag)]
      (is (= 2 (count sources)) "series:42 appears twice but dedupes to one source")
      (is (some #(= {:name "auto:series:42" :kind :show :show-id 42} %) sources))
      (is (some #(= :category (:kind %)) sources)))))

(deftest content-sources-skips-movies
  (testing "movie: strips need no collection"
    (let [grid {:strips [(strip "s1" "daily" "20:00" "22:00" (content "movie:7"))]}]
      (is (empty? (sut/content-sources grid channel-tag))))))

(deftest content-sources-category-scoped-to-channel
  (testing "the category source name is scoped by channel-tag"
    (let [grid {:strips [(strip "s1" "daily" "12:00" "13:00" (content "random:sitcom"))]}
          [source] (sut/content-sources grid channel-tag)]
      (is (= "auto:category:sitcom:channel:hua" (:name source)))
      (is (= channel-tag (:channel-tag source))))))

;; ---------------------------------------------------------------------------
;; ->slot
;; ---------------------------------------------------------------------------

(deftest slot-for-series-strip
  (testing "a sequential series strip becomes a fixed, block-fill, chronological slot"
    (let [s (strip "s1" "weekdays" "17:00" "17:30" (content "series:42" :strategy "sequential" :label "Seinfeld"))
          slot (sut/->slot s channel-tag {"auto:series:42" 101})]
      (is (nil? (:error slot)))
      (is (= "fixed" (:anchor slot)))
      (is (= "17:00:00" (:start-time slot)))
      (is (= 31 (:days-of-week slot)) "weekdays = mon+tue+wed+thu+fri = 1+2+4+8+16")
      (is (= "block" (:fill-mode slot)))
      (is (= "PT30M" (:block-duration slot)))
      (is (= "filler" (:tail-mode slot)))
      (is (= 101 (:collection-id slot)))
      (is (= "chronological" (:playback-order slot)))
      (is (= "Seinfeld" (:custom-title slot))))))

(deftest slot-for-series-strip-unresolved-collection-errors
  (testing "a series strip with no matching resolved collection is an error, not a slot"
    (let [s (strip "s1" "weekdays" "17:00" "17:30" (content "series:42"))
          slot (sut/->slot s channel-tag {})]
      (is (string? (:error slot)))
      (is (= "s1" (:strip-id slot))))))

(deftest slot-for-movie-strip-needs-no-collection
  (testing "a movie strip points media-item-id straight at the item"
    (let [s (strip "s1" "sat" "20:00" "22:00" (content "movie:7"))
          slot (sut/->slot s channel-tag {})]
      (is (nil? (:error slot)))
      (is (= 7 (:media-item-id slot)))
      (is (nil? (:collection-id slot))))))

(deftest slot-for-random-strip-uses-category-collection
  (testing "a random:<category> strip resolves to its category collection, random order"
    (let [s (strip "s1" "daily" "12:00" "13:00" (content "random:sitcom"))
          slot (sut/->slot s channel-tag {(str "auto:category:sitcom:" channel-tag) 55})]
      (is (nil? (:error slot)))
      (is (= 55 (:collection-id slot)))
      (is (= "random" (:playback-order slot))))))

(deftest slot-for-unknown-media-kind-errors
  (testing "an unrecognized media_id kind is an error, not a slot"
    (let [s (strip "s1" "daily" "12:00" "13:00" (content "bogus:1"))
          slot (sut/->slot s channel-tag {})]
      (is (string? (:error slot))))))

(deftest cross-midnight-strip-duration
  (testing "end <= start wraps past midnight when computing block-duration"
    (let [s (strip "s1" "daily" "23:00" "01:00" (content "movie:1"))
          slot (sut/->slot s channel-tag {})]
      (is (= "PT2H" (:block-duration slot))))))

;; ---------------------------------------------------------------------------
;; ->schedule
;; ---------------------------------------------------------------------------

(deftest schedule-orders-by-start-time-and-reindexes
  (testing "resolved slots are sorted by start time and reindexed from 0"
    (let [grid {:channel "Sitcom Central"
                :strips [(strip "late" "daily" "20:00" "20:30" (content "movie:2"))
                         (strip "early" "daily" "08:00" "08:30" (content "movie:1"))]}
          {:keys [name slots warnings]} (sut/->schedule grid channel-tag {})]
      (is (= "Sitcom Central (auto)" name))
      (is (empty? warnings))
      (is (= [0 1] (mapv :slot-index slots)))
      (is (= [1 2] (mapv :media-item-id slots)) "early (item 1) sorts before late (item 2)"))))

(deftest schedule-collects-warnings-for-unresolved-strips-without-failing
  (testing "an unresolved strip is dropped with a warning; other strips still sync"
    (let [grid {:channel "Sitcom Central"
                :strips [(strip "ok" "daily" "08:00" "08:30" (content "movie:1"))
                         (strip "bad" "daily" "09:00" "09:30" (content "series:99"))]}
          {:keys [slots warnings]} (sut/->schedule grid channel-tag {})]
      (is (= 1 (count slots)))
      (is (= 1 (count warnings)))
      (is (re-find #"series 99" (first warnings))))))
