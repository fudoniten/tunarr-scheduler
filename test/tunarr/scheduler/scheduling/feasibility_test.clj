(ns tunarr.scheduler.scheduling.feasibility-test
  "Tests for the deterministic feasibility checker (handoff §4.3).

   A one-week horizon (Mon 2026-01-05 → next Mon, exclusive) gives exactly 5
   weekdays, so the sequential-series thresholds land on clean integers:
   available 4 < 5 ⇒ shortfall, 5 < 6 (=5×1.2) ⇒ tight, 6 ⇒ ok."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [tunarr.scheduler.scheduling.contracts :as c]
            [tunarr.scheduler.scheduling.feasibility :as f]))

(def week-start "2026-01-05")
(def week-end   "2026-01-12")   ; exclusive ⇒ Mon-Fri = 5 weekday airings

(defn- strip [strip-id days start end media-id & {:keys [strategy priority]
                                                  :or {strategy "sequential" priority 0}}]
  {:strip_id strip-id :days days :start start :end end
   :content {:media_id media-id :strategy strategy} :priority priority})

(def catalog
  {:channel_scope "Classic Comedy"
   :total_items 100 :total_episodes 100 :movie_count 1
   :shows [{:media_id "series:seinfeld" :title "Seinfeld" :episode_count 180 :available_episode_count 6}
           {:media_id "series:thin"     :title "Thin"     :episode_count 5   :available_episode_count 5}
           {:media_id "series:short"    :title "Short"    :episode_count 4   :available_episode_count 4}]
   :genres [{:genre "sitcom" :show_count 3 :episode_count 50}
            {:genre "rare"   :show_count 1 :episode_count 3}]
   :runtime_histogram []})

(defn- grid [strips & {:keys [default?] :or {default? true}}]
  (cond-> {:channel "Classic Comedy" :strips strips}
    default? (assoc :default_content {:media_id "random:sitcom" :strategy "random"})))

(defn- finding-for [report rule-id]
  (first (filter #(= rule-id (:rule_id %)) (:strip_findings report))))

;; ---------------------------------------------------------------------------
;; Per-strip capacity — sequential series thresholds
;; ---------------------------------------------------------------------------

(deftest sequential-series-capacity
  (let [report (f/check (grid [(strip "s-ok"    "weekdays" "17:00" "18:00" "series:seinfeld")
                               (strip "s-tight" "weekdays" "12:00" "13:00" "series:thin")
                               (strip "s-short" "weekdays" "08:00" "09:00" "series:short")])
                        catalog week-start week-end)]
    (testing "slots_required counts weekday airings"
      (is (= 5 (:slots_required (finding-for report "s-ok")))))
    (testing "ample episodes ⇒ ok"
      (is (= "ok" (:status (finding-for report "s-ok"))))
      (is (= 6 (:episodes_available (finding-for report "s-ok")))))
    (testing "available within the margin ⇒ tight"
      (is (= "tight" (:status (finding-for report "s-tight")))))
    (testing "available below required ⇒ shortfall"
      (is (= "shortfall" (:status (finding-for report "s-short")))))
    (testing "headroom_ratio = available / required (thin: 5/5 = 1.0)"
      (is (== 1.0 (:headroom_ratio (finding-for report "s-tight")))))))

(deftest headroom-ratio-values
  (let [report (f/check (grid [(strip "s-ok" "weekdays" "17:00" "18:00" "series:seinfeld")])
                        catalog week-start week-end)]
    (is (== (/ 6.0 5) (:headroom_ratio (finding-for report "s-ok"))))))

;; ---------------------------------------------------------------------------
;; Pooled rotation + movies
;; ---------------------------------------------------------------------------

(deftest random-pool-capacity
  (let [report (f/check (grid [(strip "r-ok"   "weekdays" "10:00" "11:00" "random:sitcom" :strategy "random")
                               (strip "r-thin" "weekdays" "11:00" "12:00" "random:rare"   :strategy "random")
                               (strip "r-unk"  "weekdays" "13:00" "14:00" "random:mystery" :strategy "random")])
                        catalog week-start week-end)]
    (testing "a healthy pool ⇒ ok"
      (is (= "ok" (:status (finding-for report "r-ok"))))
      (is (= 50 (:episodes_available (finding-for report "r-ok")))))
    (testing "a pool below the floor ⇒ tight"
      (is (= "tight" (:status (finding-for report "r-thin")))))
    (testing "an unknown category is reported, not failed"
      (is (= "ok" (:status (finding-for report "r-unk"))))
      (is (str/includes? (:message (finding-for report "r-unk")) "not found")))))

(deftest movie-capacity
  (let [report (f/check (grid [(strip "m-once"  ["mon"]    "20:00" "22:00" "movie:classic")
                               (strip "m-multi" "weekdays" "20:00" "22:00" "movie:overplayed")])
                        catalog week-start week-end)]
    (testing "a movie airing once ⇒ ok"
      (is (= "ok" (:status (finding-for report "m-once")))))
    (testing "a movie airing repeatedly ⇒ tight"
      (is (= "tight" (:status (finding-for report "m-multi"))))
      (is (str/includes? (:message (finding-for report "m-multi")) "5 times")))))

;; ---------------------------------------------------------------------------
;; Overlap detection
;; ---------------------------------------------------------------------------

(deftest overlap-detection
  (let [report (f/check (grid [(strip "a" "weekdays" "17:00" "18:00" "series:seinfeld")
                               (strip "b" "weekdays" "17:30" "18:30" "series:thin")])
                        catalog week-start week-end)]
    (is (= 1 (count (:overlaps report))))
    (let [msg (first (:overlaps report))]
      (is (str/includes? msg "a overlaps b"))
      (is (str/includes? msg "weekdays"))
      (is (str/includes? msg "17:30-18:00")))))

(deftest no-overlap-when-days-disjoint
  (testing "same time window but disjoint days ⇒ no overlap"
    (let [report (f/check (grid [(strip "wd" "weekdays" "17:00" "18:00" "series:seinfeld")
                                 (strip "we" "weekends" "17:00" "18:00" "series:thin")])
                          catalog week-start week-end)]
      (is (empty? (:overlaps report))))))

;; ---------------------------------------------------------------------------
;; Coverage gaps
;; ---------------------------------------------------------------------------

(deftest coverage-gaps-without-default
  (testing "a grid with no default surfaces the uncovered broadcast-day time"
    (let [report (f/check (grid [(strip "day" "daily" "06:00" "22:00" "series:seinfeld")]
                                :default? false)
                          catalog week-start week-end)]
      (is (some #(str/includes? % "00:00-06:00") (:uncovered_intervals report)))
      (is (some #(str/includes? % "22:00-24:00") (:uncovered_intervals report)))
      (is (every? #(str/starts-with? % "daily") (:uncovered_intervals report))))))

(deftest no-gaps-with-default
  (testing "default_content fills everything ⇒ no uncovered intervals"
    (let [report (f/check (grid [(strip "day" "daily" "06:00" "22:00" "series:seinfeld")])
                          catalog week-start week-end)]
      (is (empty? (:uncovered_intervals report))))))

;; ---------------------------------------------------------------------------
;; overall_status rollup + contract conformance
;; ---------------------------------------------------------------------------

(deftest overall-status-rollup
  (testing "ok: everything fine, default present, no overlaps"
    (is (= "ok" (:overall_status
                 (f/check (grid [(strip "s" "weekdays" "17:00" "18:00" "series:seinfeld")])
                          catalog week-start week-end)))))
  (testing "warnings: a tight finding"
    (is (= "warnings" (:overall_status
                       (f/check (grid [(strip "t" "weekdays" "17:00" "18:00" "series:thin")])
                                catalog week-start week-end)))))
  (testing "warnings: an overlap"
    (is (= "warnings" (:overall_status
                       (f/check (grid [(strip "a" "weekdays" "17:00" "18:00" "series:seinfeld")
                                       (strip "b" "weekdays" "17:30" "18:30" "series:seinfeld")])
                                catalog week-start week-end)))))
  (testing "blocked: any shortfall dominates"
    (is (= "blocked" (:overall_status
                      (f/check (grid [(strip "ok"    "weekdays" "17:00" "18:00" "series:seinfeld")
                                      (strip "short" "weekdays" "08:00" "09:00" "series:short")])
                               catalog week-start week-end))))))

(deftest report-conforms-to-contract
  (let [report (f/check (grid [(strip "a" "weekdays" "17:00" "18:00" "series:seinfeld")
                               (strip "b" "weekends" "10:00" "12:00" "random:sitcom" :strategy "random")
                               (strip "m" ["sat"]    "20:00" "22:00" "movie:classic")])
                        catalog week-start week-end)]
    (is (nil? (c/humanize c/FeasibilityReport report)))
    (doseq [finding (:strip_findings report)]
      (is (nil? (c/humanize c/StripFeasibility finding))
          (str "non-conforming finding: " finding)))))

;; ---------------------------------------------------------------------------
;; Content policy (watershed) enforcement
;; ---------------------------------------------------------------------------

(def adult-catalog
  "Django is an adult movie, tagged in the profile so the checker can resolve it."
  (assoc catalog
         :shows (conj (:shows catalog)
                      {:media_id "movie:django" :title "Django Unchained"
                       :episode_count 1 :available_episode_count 1
                       :tags ["audience:adult"]})))

(def watershed-policy
  {:watersheds [{:dimension "audience" :value "adult"
                 :allowed_from "22:00" :allowed_to "06:00"}]})

(deftest watershed-blocks-daytime-adult-movie
  (testing "an adult movie at 18:00 (the Django-at-6PM bug) is blocked + reported"
    (let [report (f/check (grid [(strip "evening" "weekdays" "18:00" "20:00" "movie:django")])
                          adult-catalog week-start week-end watershed-policy)]
      (is (= "blocked" (:overall_status report)))
      (is (= 1 (count (:watershed_violations report))))
      (is (str/includes? (first (:watershed_violations report)) "evening"))
      (testing "the reason is mirrored into :notes for a repair endpoint"
        (is (= (:watershed_violations report) (:notes report))))
      (testing "the report still conforms to the contract"
        (is (nil? (c/humanize c/FeasibilityReport report)))))))

(deftest watershed-allows-latenight-and-ignores-non-adult
  (testing "the same movie after 22:00 is fine"
    (let [report (f/check (grid [(strip "late" "weekdays" "22:30" "23:59" "movie:django")])
                          adult-catalog week-start week-end watershed-policy)]
      (is (empty? (:watershed_violations report)))
      (is (not= "blocked" (:overall_status report)))))
  (testing "non-adult daytime content is untouched"
    (let [report (f/check (grid [(strip "s" "weekdays" "17:00" "18:00" "series:seinfeld")])
                          adult-catalog week-start week-end watershed-policy)]
      (is (empty? (:watershed_violations report)))))
  (testing "without a policy the checker behaves exactly as before"
    (let [report (f/check (grid [(strip "evening" "weekdays" "18:00" "20:00" "movie:django")])
                          adult-catalog week-start week-end)]
      (is (empty? (:watershed_violations report)))
      (is (not= "blocked" (:overall_status report))))))
