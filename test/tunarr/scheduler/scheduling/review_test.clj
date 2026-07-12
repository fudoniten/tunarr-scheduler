(ns tunarr.scheduler.scheduling.review-test
  "Tests for the schedule review / critique loop (Tunarr Scheduler half).
   Pure sample-week construction + the bounded loop with injected Tunabrain
   calls stubbed (same style as orchestration-test)."
  (:require [clojure.test :refer [deftest testing is]]
            [tunarr.scheduler.scheduling.review :as sut]))

;; series:42 = Cheers, only 5 available episodes (so a weekday sequential strip
;; over a quarter is a feasibility shortfall — used to test revision rejection).
(def profile
  {:channel_scope "Classic Comedy" :total_items 100 :total_episodes 80 :movie_count 20
   :shows [{:media_id "series:42" :title "Cheers" :episode_count 275 :available_episode_count 5}]
   :genres [{:genre "comedy" :show_count 1 :episode_count 275}]
   :tag_aggregates [{:tag "comedy" :show_count 1 :episode_count 275}]
   :runtime_histogram []})

(def channel-spec {:name "Classic Comedy" :description "vintage sitcoms"
                   :uuid "00000000-0000-0000-0000-000000000001"})

(def prime-grid
  {:channel "Classic Comedy"
   :strips [{:strip_id "prime" :days "weekdays" :start "17:00" :end "18:00"
             :content {:media_id "series:42" :strategy "sequential"}}]
   :default_content {:media_id "random:comedy" :strategy "random"}})

;; No demanding strips ⇒ always feasible.
(def feasible-grid
  {:channel "Classic Comedy"
   :strips []
   :default_content {:media_id "random:comedy" :strategy "random"}})

;; ---------------------------------------------------------------------------
;; slot-label
;; ---------------------------------------------------------------------------

(deftest slot-label-resolves-titles-and-pools
  (let [titles (sut/title-index profile)]
    (is (= "Cheers" (sut/slot-label "series:42" titles)))
    (is (= "random: comedy pool" (sut/slot-label "random:comedy" titles)))
    (is (= "series:99" (sut/slot-label "series:99" titles)) "unknown id falls back to raw")))

;; ---------------------------------------------------------------------------
;; sample-week
;; ---------------------------------------------------------------------------

(deftest sample-week-expands-and-labels
  (let [week (sut/sample-week prime-grid profile)]
    (is (seq week))
    (is (every? #(contains? #{"mon" "tue" "wed" "thu" "fri" "sat" "sun"} (:day %)) week))
    (testing "the weekday prime strip surfaces as labelled Cheers slots"
      (let [prime (filter #(and (= "17:00" (:start %)) (= "series:42" (:media_id %))) week)]
        (is (= 5 (count prime)) "one per weekday")
        (is (every? #(= "Cheers" (:label %)) prime))
        (is (every? #(= "mon" (:day %)) (filter #(= "mon" (:day %)) prime)))))
    (testing "slots carry HH:MM strings and a strategy"
      (is (every? #(re-matches #"\d{2}:\d{2}" (:start %)) week)))))

;; ---------------------------------------------------------------------------
;; run-review-loop
;; ---------------------------------------------------------------------------

(defn- review-fn-returning [& verdicts]
  "Stub review-fn that returns the given verdicts in sequence (last repeats)."
  (let [calls (atom 0)]
    (fn [_tb _opts]
      (let [i (min @calls (dec (count verdicts)))
            v (nth (vec verdicts) i)]
        (swap! calls inc)
        {:review {:verdict v :score (if (= v "pass") 0.9 0.4)
                  :summary "stub" :findings (if (= v "pass") []
                                              [{:aspect "series-usage" :severity "major"
                                                :message "too generic"}])}}))))

(deftest review-passes-first-time-no-revision
  (let [revises (atom 0)
        out (sut/run-review-loop
             {:tunabrain ::tb :channel-spec channel-spec :profile profile
              :skeleton nil :grid feasible-grid :hstart "2026-01-01" :hend "2026-04-01"
              :review-fn (review-fn-returning "pass")
              :revise-fn (fn [_ _] (swap! revises inc) {:grid feasible-grid})})]
    (is (= "pass" (:verdict (:review out))))
    (is (= 0 (:reviews out)))
    (is (= 0 @revises) "a passing review must not revise")))

(deftest review-fails-then-revises-then-passes
  (let [revises (atom 0)
        out (sut/run-review-loop
             {:tunabrain ::tb :channel-spec channel-spec :profile profile
              :skeleton nil :grid feasible-grid :hstart "2026-01-01" :hend "2026-04-01"
              :review-fn (review-fn-returning "fail" "pass")
              ;; revised grid stays feasible, so the loop accepts it and re-reviews
              :revise-fn (fn [_ _] (swap! revises inc) {:grid feasible-grid})})]
    (is (= 1 @revises) "exactly one revision")
    (is (= "pass" (:verdict (:review out))))
    (is (= 1 (:reviews out)))))

(deftest review-loop-is-bounded
  (let [revises (atom 0)
        out (sut/run-review-loop
             {:tunabrain ::tb :channel-spec channel-spec :profile profile
              :skeleton nil :grid feasible-grid :hstart "2026-01-01" :hend "2026-04-01"
              :review-fn (review-fn-returning "fail")            ; never passes
              :revise-fn (fn [_ _] (swap! revises inc) {:grid feasible-grid})
              :max-reviews 2})]
    (is (= "fail" (:verdict (:review out))))
    (is (= 2 (:reviews out)) "stops at max-reviews")
    (is (= 2 @revises))))

(deftest revision-that-breaks-feasibility-is-rejected
  (let [out (sut/run-review-loop
             {:tunabrain ::tb :channel-spec channel-spec :profile profile
              :skeleton nil :grid feasible-grid :hstart "2026-01-01" :hend "2026-04-01"
              :review-fn (review-fn-returning "fail")
              ;; the "revision" reintroduces a capacity shortfall (series:42 x5
              ;; every weekday) ⇒ feasibility blocked ⇒ revision rejected
              :revise-fn (fn [_ _] {:grid prime-grid})})]
    (is (:revision-rejected? out))
    (is (= feasible-grid (:grid out)) "keeps the prior feasible grid, not the broken revision")))
