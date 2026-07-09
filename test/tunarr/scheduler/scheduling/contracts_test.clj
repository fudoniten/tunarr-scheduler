(ns tunarr.scheduler.scheduling.contracts-test
  "Conformance tests for the layered-grid contracts.

   The example maps below are transcribed from the handoff spec
   (tunabrain docs/handoff-tunarr-pseudovision.md §2) and round-tripped through
   Cheshire to prove the schemas accept the exact wire format (snake_case keys)
   that Tunabrain produces and consumes."
  (:require [clojure.test :refer [deftest testing is]]
            [cheshire.core :as json]
            [tunarr.scheduler.scheduling.contracts :as c]))

(defn- round-trip
  "Serialize to JSON and parse back with keyword keys, mimicking the
   tunabrain client's `json/parse-string body true`."
  [m]
  (json/parse-string (json/generate-string m) true))

(defn- check
  "Assert `value` conforms to `schema` both directly and after a JSON round-trip."
  [schema value]
  (is (nil? (c/humanize schema value))
      "value should conform as authored")
  (is (c/valid? schema (round-trip value))
      "value should still conform after a JSON round-trip"))

;; ---------------------------------------------------------------------------
;; Example fixtures (verbatim from the handoff spec §2)
;; ---------------------------------------------------------------------------

(def content-example
  {:media_id "series:seinfeld" :strategy "sequential" :marathon false
   :category_filters [] :label "Seinfeld at Five" :notes []})

(def catalog-profile-example
  {:channel_scope "Classic Comedy"
   :total_items 900
   :total_episodes 880
   :movie_count 20
   :shows [{:media_id "series:seinfeld"
            :title "Seinfeld"
            :genres ["comedy" "sitcom"]
            :episode_count 180
            :available_episode_count 180
            :avg_runtime_minutes 22.0
            :tags ["classic"]}]
   :genres [{:genre "comedy" :show_count 2 :episode_count 450}]
   :tag_aggregates [{:tag "genre:comedy" :show_count 2 :episode_count 450}]
   :runtime_histogram [{:label "20-30min" :min_minutes 20 :max_minutes 30 :item_count 450}
                       {:label "210+min" :min_minutes 210 :max_minutes nil :item_count 1}]
   :tag_runtime_histograms [{:tag "genre:movie"
                             :buckets [{:label "90-105min" :min_minutes 90 :max_minutes 105
                                       :item_count 12}]}]
   :generated_at "2026-06-24T12:00:00"})

(def grid-example
  {:channel "Classic Comedy"
   :broadcast_day_start "06:00"
   :skeleton {:channel "Classic Comedy"
              :blocks [{:name "prime" :start "17:00" :end "22:00" :role "marquee sitcoms"
                        :genre_focus ["comedy"] :rationale "…"}]}
   :strips [{:strip_id "classic_comedy-prime-0"
             :days "weekdays"
             :start "17:00"
             :end "18:00"
             :content {:media_id "series:seinfeld" :strategy "sequential"
                       :marathon false :category_filters [] :label "Seinfeld at Five" :notes []}
             :priority 0
             :daypart "prime"}]
   :default_content {:media_id "random:sitcom" :strategy "random"
                     :marathon false :category_filters [] :label nil :notes []}})

(def override-example
  {:override_id "classic_comedy-2026-01-ovr-0"
   :scope {:date "2026-01-10"}
   :start "10:00"
   :end "22:00"
   :content {:media_id "series:cheers" :strategy "sequential" :marathon true
             :category_filters [] :label "Cheers Marathon" :notes []}
   :mode "replace"
   :priority 0
   :note "Operator request"})

(def feasibility-report-example
  {:horizon_start "2026-01-01"
   :horizon_end "2026-04-01"
   :overall_status "blocked"
   :strip_findings [{:rule_id "classic_comedy-prime-0" :media_id "series:seinfeld"
                     :slots_required 65 :episodes_available 180 :headroom_ratio 2.77
                     :status "ok" :message ""}]
   :overlaps ["classic_comedy-prime-0 overlaps classic_comedy-prime-1 on weekdays 17:30-18:00"]
   :uncovered_intervals ["weekdays 02:00-06:00"]
   :notes []})

(def daily-slot-example
  {:start_time "2026-01-10T10:00:00"
   :end_time "2026-01-10T22:00:00"
   :media_id "series:cheers"
   :media_selection_strategy "sequential"
   :category_filters []
   :notes []})

;; ---------------------------------------------------------------------------
;; Conformance
;; ---------------------------------------------------------------------------

(deftest spec-examples-conform
  (testing "Content"           (check c/Content content-example))
  (testing "CatalogProfile"    (check c/CatalogProfile catalog-profile-example))
  (testing "Grid"              (check c/Grid grid-example))
  (testing "Override"          (check c/ScheduleOverride override-example))
  (testing "FeasibilityReport" (check c/FeasibilityReport feasibility-report-example))
  (testing "DailySlot"         (check c/DailySlot daily-slot-example)))

(deftest days-pattern-variants
  (testing "named groups"
    (is (c/valid? c/DaysPattern "daily"))
    (is (c/valid? c/DaysPattern "weekdays"))
    (is (c/valid? c/DaysPattern "weekends")))
  (testing "explicit weekday list"
    (is (c/valid? c/DaysPattern ["mon" "wed" "fri"])))
  (testing "rejects unknown tokens"
    (is (not (c/valid? c/DaysPattern "fortnightly")))
    (is (not (c/valid? c/DaysPattern ["funday"])))))

(deftest override-scope-is-exactly-one-of
  (testing "single date"
    (is (c/valid? c/OverrideScope {:date "2026-01-10"})))
  (testing "bounded recurring window"
    (is (c/valid? c/OverrideScope {:days "weekends"
                                   :effective_start "2026-01-01"
                                   :effective_end "2026-01-31"})))
  (testing "recurring window bounds are optional (unbounded)"
    (is (c/valid? c/OverrideScope {:days ["fri"]}))
    (is (c/valid? c/OverrideScope {:days "weekends" :effective_start "2026-01-01"})))
  (testing "rejects mixing the two shapes"
    (is (not (c/valid? c/OverrideScope {:date "2026-01-10" :days "weekends"})))))

(deftest clock-time-and-cross-midnight
  (testing "valid HH:MM"
    (is (c/valid? c/ClockTime "00:00"))
    (is (c/valid? c/ClockTime "23:59")))
  (testing "rejects out-of-range / malformed"
    (is (not (c/valid? c/ClockTime "24:00")))
    (is (not (c/valid? c/ClockTime "6:00")))
    (is (not (c/valid? c/ClockTime "17:60"))))
  (testing "a cross-midnight strip (end <= start) still conforms structurally"
    (is (c/valid? c/GridStrip
                  (-> (first (:strips grid-example))
                      (assoc :start "22:00" :end "06:00"))))))
