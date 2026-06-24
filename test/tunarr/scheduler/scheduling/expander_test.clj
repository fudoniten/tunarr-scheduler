(ns tunarr.scheduler.scheduling.expander-test
  "Conformance tests for the deterministic expander.

   These reproduce the enumerated cases the handoff spec (§4.2) calls out as the
   golden suite: determinism, week-to-week identity, partial override, the
   specificity cascade, cross-midnight, default fill, and the empty grid. The
   upstream tunabrain tests/test_grid_expander.py should still be ported verbatim
   once accessible."
  (:require [clojure.test :refer [deftest testing is]]
            [tunarr.scheduler.scheduling.contracts :as c]
            [tunarr.scheduler.scheduling.expander :as e]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn- content [media-id strategy & {:keys [label]}]
  {:media_id media-id :strategy strategy :marathon false
   :category_filters [] :label label :notes []})

(defn- strip [strip-id days start end media-id & {:keys [priority strategy]
                                                  :or {priority 0 strategy "sequential"}}]
  {:strip_id strip-id :days days :start start :end end
   :content (content media-id strategy) :priority priority :daypart "all"})

(def base-grid
  "Weekdays 17:00–18:00 Seinfeld, plus a nightly overnight strip 22:00–06:00,
   with a random-sitcom default filling everything else."
  {:channel "Classic Comedy"
   :broadcast_day_start "06:00"
   :skeleton {:channel "Classic Comedy" :blocks []}
   :strips [(strip "prime-seinfeld" "weekdays" "17:00" "18:00" "series:seinfeld")
            (strip "overnight"       "daily"    "22:00" "06:00" "series:latenight")]
   :default_content (content "random:sitcom" "random")})

;; A week that starts on a Monday (2026-01-05 is a Monday).
(def week1-start "2026-01-05")
(def week1-end   "2026-01-12")  ; exclusive (next Monday)
(def week2-start "2026-01-12")
(def week2-end   "2026-01-19")

(defn- media-ids [slots] (mapv :media_id slots))

;; ---------------------------------------------------------------------------
;; Determinism & purity
;; ---------------------------------------------------------------------------

(deftest deterministic
  (testing "same inputs ⇒ identical output, every time"
    (let [a (e/expand base-grid [] week1-start week1-end)
          b (e/expand base-grid [] week1-start week1-end)]
      (is (= a b)))))

(deftest output-conforms-to-contract
  (let [slots (e/expand base-grid [] week1-start week1-end)]
    (is (seq slots))
    (doseq [slot slots]
      (is (nil? (c/humanize c/DailySlot slot)) (str "non-conforming slot: " slot)))))

;; ---------------------------------------------------------------------------
;; Week-to-week identity (the grid is frozen)
;; ---------------------------------------------------------------------------

(deftest week-to-week-identity
  (testing "with no overrides, two consecutive weeks are identical modulo dates"
    (let [w1 (e/expand base-grid [] week1-start week1-end)
          w2 (e/expand base-grid [] week2-start week2-end)
          strip-dates (fn [slots] (mapv #(-> % :start_time (subs 11)) slots))]
      (is (= (count w1) (count w2)))
      (is (= (media-ids w1) (media-ids w2)))
      ;; Same wall-clock times each week, only the calendar date differs.
      (is (= (strip-dates w1) (strip-dates w2))))))

;; ---------------------------------------------------------------------------
;; Cross-midnight
;; ---------------------------------------------------------------------------

(deftest cross-midnight-strip
  (testing "a 22:00–06:00 strip yields a slot spanning midnight"
    (let [slots (e/expand base-grid [] week1-start week1-end)
          overnight (filter #(= "series:latenight" (:media_id %)) slots)]
      (is (seq overnight))
      ;; At least one overnight slot starts at 22:00 on one date and ends 06:00
      ;; on the next calendar day.
      (is (some (fn [{:keys [start_time end_time]}]
                  (and (re-find #"T22:00:00$" start_time)
                       (re-find #"T06:00:00$" end_time)
                       (not= (subs start_time 0 10) (subs end_time 0 10))))
                overnight)))))

(deftest leading-day-covers-early-hours
  (testing "the prior day's overnight strip covers 00:00–06:00 of range-start"
    (let [slots (e/expand base-grid [] week1-start week1-end)
          first-slot (first slots)]
      ;; Window opens at Mon 00:00; the Sunday-night overnight strip should own it.
      (is (= (str week1-start "T00:00:00") (:start_time first-slot)))
      (is (= "series:latenight" (:media_id first-slot))))))

;; ---------------------------------------------------------------------------
;; Default fill
;; ---------------------------------------------------------------------------

(deftest default-fill
  (testing "uncovered time is filled with default_content"
    (let [slots (e/expand base-grid [] week1-start week1-end)]
      (is (some #(= "random:sitcom" (:media_id %)) slots))))
  (testing "no gaps when a default is present: slots tile the window contiguously"
    (let [slots (e/expand base-grid [] week1-start week1-end)
          sorted (sort-by :start_time slots)]
      (is (= (str week1-start "T00:00:00") (:start_time (first sorted))))
      (is (= (str week1-end   "T00:00:00") (:end_time (last sorted))))
      (doseq [[a b] (partition 2 1 sorted)]
        (is (= (:end_time a) (:start_time b))
            "adjacent slots must be contiguous")))))

(deftest empty-grid
  (testing "no strips and no default ⇒ no slots"
    (let [grid {:channel "Empty" :broadcast_day_start "06:00"
                :skeleton {:channel "Empty" :blocks []}
                :strips [] :default_content nil}]
      (is (= [] (e/expand grid [] week1-start week1-end)))))
  (testing "no strips but a default ⇒ a single default slot spanning the window"
    (let [grid {:channel "Default only" :broadcast_day_start "06:00"
                :skeleton {:channel "Default only" :blocks []}
                :strips [] :default_content (content "random:sitcom" "random")}
          slots (e/expand grid [] week1-start week1-end)]
      (is (= 1 (count slots)))
      (is (= (str week1-start "T00:00:00") (:start_time (first slots))))
      (is (= (str week1-end   "T00:00:00") (:end_time (first slots)))))))

;; ---------------------------------------------------------------------------
;; Partial override — a Saturday marathon leaves the overnight strip intact
;; ---------------------------------------------------------------------------

(def saturday-marathon
  {:override_id "cheers-sat"
   :scope {:date "2026-01-10"}      ; the Saturday in week 1
   :start "10:00" :end "22:00"
   :content (content "series:cheers" "sequential" :label "Cheers Marathon")
   :mode "replace" :priority 0 :note "Operator request"})

(deftest partial-override-leaves-overnight-intact
  (let [slots (e/expand base-grid [saturday-marathon] week1-start week1-end)
        sat   (filter #(= "2026-01-10" (subs (:start_time %) 0 10)) slots)]
    (testing "the Saturday daytime 10:00–22:00 is the marathon"
      (is (some #(and (= "series:cheers" (:media_id %))
                      (re-find #"T10:00:00$" (:start_time %))
                      (re-find #"T22:00:00$" (:end_time %)))
                sat)))
    (testing "the Saturday-night overnight strip (22:00 →) is untouched"
      (is (some #(and (= "series:latenight" (:media_id %))
                      (re-find #"T22:00:00$" (:start_time %)))
                sat)))
    (testing "the override does not bleed into other days"
      (let [other-days (remove #(= "2026-01-10" (subs (:start_time %) 0 10)) slots)]
        (is (not-any? #(= "series:cheers" (:media_id %)) other-days))))))

;; ---------------------------------------------------------------------------
;; Specificity cascade — date > explicit list > named group > daily; override > strip
;; ---------------------------------------------------------------------------

(deftest specificity-cascade
  (let [grid {:channel "Cascade" :broadcast_day_start "06:00"
              :skeleton {:channel "Cascade" :blocks []}
              :strips [(strip "daily-base" "daily" "12:00" "13:00" "series:base")]
              :default_content nil}
        named  {:override_id "named"  :scope {:days "weekends" :effective_start "2026-01-01" :effective_end "2026-01-31"}
                :start "12:00" :end "13:00" :content (content "series:named" "sequential")
                :mode "replace" :priority 0 :note nil}
        dated  {:override_id "dated"  :scope {:date "2026-01-10"}
                :start "12:00" :end "13:00" :content (content "series:dated" "sequential")
                :mode "replace" :priority 0 :note nil}
        slot-on (fn [slots date] (->> slots (filter #(= date (subs (:start_time %) 0 10))) first :media_id))]
    (testing "a base strip wins when nothing overrides it"
      (is (= "series:base" (slot-on (e/expand grid [] week1-start week1-end) "2026-01-05"))))
    (testing "an override beats the base strip (higher layer_rank)"
      (is (= "series:named" (slot-on (e/expand grid [named] week1-start week1-end) "2026-01-10"))))
    (testing "a date-scoped override beats a day-pattern override on the same day"
      (let [slots (e/expand grid [named dated] week1-start week1-end)]
        ;; Saturday the 10th: both match; the specific date wins.
        (is (= "series:dated" (slot-on slots "2026-01-10")))
        ;; Sunday the 11th: only the named (weekends) override matches.
        (is (= "series:named" (slot-on slots "2026-01-11")))))))

(deftest priority-breaks-same-layer-ties
  (testing "within the base grid, a higher-priority strip wins an overlap"
    (let [grid {:channel "Prio" :broadcast_day_start "06:00"
                :skeleton {:channel "Prio" :blocks []}
                :strips [(strip "lo" "daily" "12:00" "14:00" "series:lo" :priority 0)
                         (strip "hi" "daily" "13:00" "14:00" "series:hi" :priority 5)]
                :default_content nil}
          slots (e/expand grid [] week1-start week1-end)
          mon   (filter #(= "2026-01-05" (subs (:start_time %) 0 10)) slots)]
      ;; 12:00–13:00 belongs to lo; 13:00–14:00 is won by the higher-priority hi.
      (is (some #(and (= "series:lo" (:media_id %)) (re-find #"T12:00:00$" (:start_time %))) mon))
      (is (some #(and (= "series:hi" (:media_id %)) (re-find #"T13:00:00$" (:start_time %))) mon)))))

(deftest interior-boundary-remerges
  (testing "a lower-precedence rule that loses everywhere doesn't fragment the winner"
    (let [grid {:channel "Merge" :broadcast_day_start "06:00"
                :skeleton {:channel "Merge" :blocks []}
                :strips [(strip "wide" "daily" "12:00" "18:00" "series:wide" :priority 9)
                         (strip "narrow" "daily" "14:00" "15:00" "series:narrow" :priority 0)]
                :default_content nil}
          slots (e/expand grid [] week1-start week1-end)
          mon   (filter #(and (= "2026-01-05" (subs (:start_time %) 0 10))
                              (= "series:wide" (:media_id %))) slots)]
      ;; Despite the narrow strip's boundaries at 14:00/15:00, the wide winner
      ;; emits as ONE 12:00–18:00 slot, not three fragments.
      (is (= 1 (count mon)))
      (is (re-find #"T12:00:00$" (:start_time (first mon))))
      (is (re-find #"T18:00:00$" (:end_time (first mon)))))))
