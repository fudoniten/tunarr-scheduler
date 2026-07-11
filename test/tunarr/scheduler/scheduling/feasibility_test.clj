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
    (testing "a category that exists in no tag view of the profile ⇒ shortfall (hallucinated tag)"
      (is (= "shortfall" (:status (finding-for report "r-unk"))))
      (is (str/includes? (:message (finding-for report "r-unk")) "does not exist")))))

;; ---------------------------------------------------------------------------
;; Tag existence — a random:<category> must name a real tag in the profile
;; (the media report the channel is scheduled against). See feasibility/
;; category-known?. Guards against the LLM scheduling a hallucinated category
;; like "sci-fi-and-fantasy" that has no media behind it.
;; ---------------------------------------------------------------------------

(deftest hallucinated-category-is-a-shortfall
  (testing "a random:<category> whose category exists in no tag view of the
            profile is flagged as a shortfall — the reported bug (scheduling
            'sci-fi-and-fantasy' when no such tag exists), which blocks the grid"
    (let [report (f/check (grid [(strip "ghost" "weekdays" "20:00" "21:00"
                                        "random:sci-fi-and-fantasy" :strategy "random")])
                          catalog week-start week-end)
          found  (finding-for report "ghost")]
      (is (= "shortfall" (:status found)))
      (is (str/includes? (:message found) "does not exist"))
      (is (= "blocked" (:overall_status report))
          "a hallucinated tag blocks the grid just like any capacity shortfall"))))

(deftest real-category-behind-a-prefixed-tag-aggregate-is-not-hallucinated
  (testing "a bare category ('comedy') that exists only as a dimension-prefixed
            tag_aggregate ('genre:comedy') is a REAL tag — counted, not flagged.
            Guards the existence check against false-positiving on the bare-vs-
            prefixed mismatch (the same reason the pool count now matches too)"
    (let [cat    (assoc catalog :tag_aggregates
                        [{:tag "genre:comedy" :show_count 4 :episode_count 40}])
          report (f/check (grid [(strip "com" "weekdays" "10:00" "11:00"
                                        "random:comedy" :strategy "random")])
                          cat week-start week-end)
          found  (finding-for report "com")]
      (is (= "ok" (:status found)))
      (is (= 40 (:episodes_available found))
          "the prefixed tag_aggregate's episode_count is read for the pool check"))))

;; ---------------------------------------------------------------------------
;; Duration fit (random:<category> strips, tag_runtime_histograms)
;; ---------------------------------------------------------------------------

(def catalog-with-histograms
  (assoc catalog
         :tag_runtime_histograms
         [{:tag "genre:movie"
           :buckets [{:label "90-105min" :min_minutes 90 :max_minutes 105 :item_count 12}
                     {:label "180-195min" :min_minutes 180 :max_minutes 195 :item_count 1}]}
          {:tag "genre:sitcom"
           :buckets [{:label "15-30min" :min_minutes 15 :max_minutes 30 :item_count 200}]}
          {:tag "genre:rare-length"
           :buckets [{:label "45-60min" :min_minutes 45 :max_minutes 60 :item_count 2}]}]))

(deftest duration-fit-ok-when-content-exists-near-strip-length
  (testing "a 90-minute random:movie strip is fine when the category has plenty near that length"
    (let [report (f/check (grid [(strip "movie-slot" "weekdays" "20:00" "21:30" "random:movie"
                                        :strategy "random")])
                          catalog-with-histograms week-start week-end)]
      (is (= "ok" (:status (finding-for report "movie-slot")))))))

(deftest duration-fit-shortfall-when-nothing-near-strip-length
  (testing "a 60-minute random:movie strip is a shortfall when the category's
            content is all 90+/180+ minutes — exactly the reported bug
            (a movie strip sized for nothing the category actually has)"
    (let [report (f/check (grid [(strip "movie-slot" "weekdays" "20:00" "21:00" "random:movie"
                                        :strategy "random")])
                          catalog-with-histograms week-start week-end)
          found (finding-for report "movie-slot")]
      (is (= "shortfall" (:status found)))
      (is (str/includes? (:message found) "no 'movie' content within"))
      (is (= "blocked" (:overall_status report))
          "a duration shortfall blocks overall_status same as any other shortfall"))))

(deftest duration-fit-tight-when-few-items-near-strip-length
  (testing "a strip whose category has only a couple of matching-length items
            (below duration-fit-floor) is tight, not a full shortfall"
    (let [report (f/check (grid [(strip "rare-slot" "weekdays" "20:00" "21:00" "random:rare-length"
                                        :strategy "random")])
                          catalog-with-histograms week-start week-end)]
      (is (= "tight" (:status (finding-for report "rare-slot")))))))

(deftest duration-fit-combines-with-pool-floor-taking-the-worse-status
  (testing "random:rare is already 'tight' on pool-floor (episode_count 3 < 10);
            when it ALSO has no matching-length content, the worse (shortfall)
            status wins and both messages are present"
    (let [catalog-both (assoc catalog-with-histograms
                              :tag_runtime_histograms
                              (conj (:tag_runtime_histograms catalog-with-histograms)
                                    {:tag "genre:rare" :buckets []}))
          report (f/check (grid [(strip "r-thin" "weekdays" "11:00" "12:00" "random:rare"
                                        :strategy "random")])
                          catalog-both week-start week-end)
          found (finding-for report "r-thin")]
      (is (= "shortfall" (:status found))
          "duration shortfall (empty buckets) outranks the pool-floor 'tight'")
      (is (str/includes? (:message found) "small pool"))
      (is (str/includes? (:message found) "no 'rare' content within")))))

(deftest duration-fit-skipped-when-no-histogram-data-for-category
  (testing "a category with no tag_runtime_histograms entry at all is not
            penalized — nothing to check against, so pool-floor status alone
            stands (matches the pre-existing 'unknown category' behavior)"
    (let [report (f/check (grid [(strip "r-ok" "weekdays" "10:00" "11:00" "random:sitcom"
                                        :strategy "random")])
                          catalog week-start week-end) ; original `catalog`, no histograms at all
          found (finding-for report "r-ok")]
      (is (= "ok" (:status found))))))

(deftest duration-fit-handles-cross-midnight-strips
  (testing "a strip crossing midnight still computes a sane wall-clock duration"
    (let [report (f/check (grid [(strip "late-movie" "weekdays" "23:15" "00:45" "random:movie"
                                        :strategy "random")])
                          catalog-with-histograms week-start week-end)]
      ;; 23:15 -> 00:45 is 90 minutes, which the movie histogram has plenty of.
      (is (= "ok" (:status (finding-for report "late-movie")))))))

(deftest category-known-only-via-runtime-histogram-is-not-hallucinated
  (testing "a category present only in tag_runtime_histograms (no genre/aggregate
            episode_count) is a real tag — capacity is 'not assessed', not a
            shortfall (catalog-with-histograms has 'genre:movie' but no 'movie'
            genre/aggregate)"
    (let [report (f/check (grid [(strip "mv" "weekdays" "20:00" "21:30"
                                        "random:movie" :strategy "random")])
                          catalog-with-histograms week-start week-end)
          found  (finding-for report "mv")]
      (is (= "ok" (:status found)))
      (is (str/includes? (:message found) "not assessed")))))

;; ---------------------------------------------------------------------------
;; Duration-fit as a REPETITION RATE, not a raw count. A short slot's fitting
;; pool must be judged against how often the grid airs a slot of that length
;; (summed across same-category strips), or a handful of items gets ground round
;; every day — the reported truncated-Simpsons-15×/week bug.
;; ---------------------------------------------------------------------------

;; comedy's whole-category pool is healthy (episode_count 300, well over the
;; pool-floor), so the pool-floor check stays 'ok' and these tests isolate the
;; duration-repetition signal. But only 5 comedies are within a half-hour.
(def catalog-thin-shorts
  (assoc catalog
         :genres [{:genre "comedy" :show_count 3 :episode_count 300}]
         :tag_runtime_histograms
         [{:tag "genre:comedy"
           :buckets [{:label "15-30min" :min_minutes 15 :max_minutes 30 :item_count 5}
                     {:label "90-105min" :min_minutes 90 :max_minutes 105 :item_count 40}]}]))

;; distinct, non-overlapping half-hour weekday windows, so these exercise the
;; duration-repetition signal without also tripping the time-overlap detector.
(def ^:private half-hour-windows [["12:00" "12:30"] ["12:30" "13:00"] ["13:00" "13:30"]])

(defn- short-comedy [id days [start end]]
  (strip id days start end "random:comedy" :strategy "random"))

(defn- n-short-comedy-strips [n days]
  (mapv (fn [i] (short-comedy (str "com-" i) days (nth half-hour-windows i)))
        (range n)))

(deftest single-occasional-short-slot-is-fine
  (testing "one half-hour comedy slot once a week against the 5-item pool is ok —
            we do want to schedule short content occasionally"
    (let [report (f/check (grid [(short-comedy "com-sun" ["sun"] (first half-hour-windows))])
                          catalog-thin-shorts week-start week-end)]
      (is (= "ok" (:status (finding-for report "com-sun")))))))

(deftest two-short-slots-a-day-is-tight
  (testing "two half-hour comedy strips every weekday ⇒ ~10 airings/week on 5
            items ⇒ repeats ~2×/week ⇒ tight (a warning)"
    (let [report (f/check (grid (n-short-comedy-strips 2 "weekdays"))
                          catalog-thin-shorts week-start week-end)]
      (is (= "tight" (:status (finding-for report "com-0"))))
      (is (= "tight" (:status (finding-for report "com-1")))))))

(deftest several-short-slots-a-day-is-a-shortfall
  (testing "three half-hour comedy strips every weekday ⇒ ~15 airings/week on 5
            items ⇒ each repeats ~3×/week ⇒ shortfall that blocks the grid, so
            the repair loop thins the frequency (the reported bug)"
    (let [report (f/check (grid (n-short-comedy-strips 3 "weekdays"))
                          catalog-thin-shorts week-start week-end)
          found  (finding-for report "com-0")]
      (is (= "shortfall" (:status found)))
      (is (str/includes? (:message found) "repeat"))
      (is (= "blocked" (:overall_status report))))))

(deftest short-slots-with-a-deep-pool-are-not-penalized
  (testing "the flag is about pool-vs-frequency, not 'short is bad': three
            half-hour comedy strips a day against a 100-item half-hour pool are
            fine — each item airs well under once a week"
    (let [deep   (assoc-in catalog-thin-shorts
                           [:tag_runtime_histograms 0 :buckets 0 :item_count] 100)
          report (f/check (grid (n-short-comedy-strips 3 "weekdays"))
                          deep week-start week-end)]
      (is (= "ok" (:status (finding-for report "com-0")))))))

(deftest long-and-short-slots-of-one-category-are-judged-separately
  (testing "a 90-minute comedy slot draws from the deep 40-item movie-length
            bucket, so it stays ok even while the half-hour slots are starved —
            demand only sums strips whose fit-windows overlap"
    (let [report (f/check (grid (conj (n-short-comedy-strips 3 "weekdays")
                                      (strip "com-long" "weekdays" "20:00" "21:30"
                                             "random:comedy" :strategy "random")))
                          catalog-thin-shorts week-start week-end)]
      (is (= "shortfall" (:status (finding-for report "com-0")))
          "the half-hour slots are still starved")
      (is (= "ok" (:status (finding-for report "com-long")))
          "the 90-minute slot draws from a different, deep bucket"))))

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
