(ns tunarr.scheduler.scheduling.expander-golden-test
  "Verbatim port of tunabrain tests/test_grid_expander.py — the golden
   conformance suite for the deterministic expander. Each deftest mirrors one
   reference test; the fixture mirrors the reference `_seinfeld_grid`.

   These pin the cross-language contract: determinism, week-to-week identity,
   weekday matching, partial overrides, the specificity cascade, default fill,
   and the empty grid. Keep these in lockstep with the Python suite."
  (:require [clojure.test :refer [deftest testing is]]
            [tunarr.scheduler.scheduling.expander :as e])
  (:import [java.time LocalDateTime]))

;; ---------------------------------------------------------------------------
;; Fixture — mirrors _seinfeld_grid():
;;   • Seinfeld weekdays 17:00-18:00
;;   • Random sitcoms overnight 22:00-10:00 daily (wraps midnight)
;;   • default_content fills the rest with random sitcoms
;; ---------------------------------------------------------------------------

(def seinfeld-grid
  {:channel "Classic Comedy"
   :strips [{:strip_id "seinfeld-prime"
             :days "weekdays" :start "17:00" :end "18:00"
             :content {:media_id "series:seinfeld" :strategy "sequential"}}
            {:strip_id "overnight-sitcoms"
             :days "daily" :start "22:00" :end "10:00"
             :content {:media_id "random:sitcom" :strategy "random"}}]
   :default_content {:media_id "random:sitcom" :strategy "random"}})

;; Helpers mirroring the reference's datetime introspection.
(defn- ldt ^LocalDateTime [s] (LocalDateTime/parse s))
(defn- weekday "Monday=0 .. Sunday=6, like Python's date.weekday()." [slot]
  (-> (ldt (:start_time slot)) .getDayOfWeek .getValue dec))
(defn- hhmm [t] (subs t 11 16))

;; ---------------------------------------------------------------------------
;; test_expansion_is_deterministic
;; ---------------------------------------------------------------------------

(deftest expansion-is-deterministic
  (let [first  (e/expand seinfeld-grid [] "2026-01-05" "2026-01-12")
        second (e/expand seinfeld-grid [] "2026-01-05" "2026-01-12")]
    (is (= first second))))

;; ---------------------------------------------------------------------------
;; test_weeks_are_identical_without_overrides
;; ---------------------------------------------------------------------------

(deftest weeks-are-identical-without-overrides
  (let [week1 (e/expand seinfeld-grid [] "2026-01-05" "2026-01-12")
        week2 (e/expand seinfeld-grid [] "2026-01-12" "2026-01-19")
        shape (fn [slots]
                (mapv (fn [s] [(weekday s) (hhmm (:start_time s)) (hhmm (:end_time s)) (:media_id s)])
                      slots))]
    (is (= (shape week1) (shape week2)))))

;; ---------------------------------------------------------------------------
;; test_seinfeld_airs_on_weekdays_not_weekends
;; ---------------------------------------------------------------------------

(deftest seinfeld-airs-on-weekdays-not-weekends
  (let [slots (e/expand seinfeld-grid [] "2026-01-05" "2026-01-12")
        seinfeld (filter #(= "series:seinfeld" (:media_id %)) slots)]
    (is (= 5 (count seinfeld)))
    (is (every? #(< (weekday %) 5) seinfeld))
    (is (every? #(= "17:00" (hhmm (:start_time %))) seinfeld))))

;; ---------------------------------------------------------------------------
;; test_override_replaces_only_its_window
;; ---------------------------------------------------------------------------

(deftest override-replaces-only-its-window
  (let [override {:override_id "cheers-marathon"
                  :scope {:date "2026-01-10"}            ; the Saturday
                  :start "10:00" :end "22:00"
                  :content {:media_id "series:cheers" :strategy "sequential" :marathon true}}
        slots (e/expand seinfeld-grid [override] "2026-01-10" "2026-01-11")
        cheers (filter #(= "series:cheers" (:media_id %)) slots)
        after  (filter #(= "22:00" (hhmm (:start_time %))) slots)]
    (is (= 1 (count cheers)))
    (is (= "10:00" (hhmm (:start_time (first cheers)))))
    (is (= "22:00" (hhmm (:end_time (first cheers)))))
    ;; The overnight sitcom strip (22:00 onward) survives after the marathon.
    (is (and (seq after) (= "random:sitcom" (:media_id (first after)))))))

;; ---------------------------------------------------------------------------
;; test_specific_date_override_outranks_recurring_override
;; ---------------------------------------------------------------------------

(deftest specific-date-override-outranks-recurring-override
  (let [recurring {:override_id "friday-movie"
                   :scope {:days ["fri"]}
                   :start "19:00" :end "21:00"
                   :content {:media_id "movie:generic-comedy"}}
        dated     {:override_id "special-premiere"
                   :scope {:date "2026-01-09"}           ; a Friday
                   :start "19:00" :end "21:00"
                   :content {:media_id "movie:special-premiere"}}
        slots (e/expand seinfeld-grid [recurring dated] "2026-01-09" "2026-01-10")
        evening (filter #(= "19:00" (hhmm (:start_time %))) slots)]
    (is (and (seq evening) (= "movie:special-premiere" (:media_id (first evening)))))))

;; ---------------------------------------------------------------------------
;; test_no_gaps_when_default_content_present
;; ---------------------------------------------------------------------------

(deftest no-gaps-when-default-content-present
  (let [slots (sort-by :start_time
                       (e/expand seinfeld-grid [] "2026-01-06" "2026-01-07"))]  ; a Tuesday
    (doseq [[a b] (partition 2 1 slots)]
      (is (= (:end_time a) (:start_time b))))))

;; ---------------------------------------------------------------------------
;; test_empty_grid_without_default_yields_nothing
;; ---------------------------------------------------------------------------

(deftest empty-grid-without-default-yields-nothing
  (let [grid {:channel "Empty" :strips []}]
    (is (= [] (e/expand grid [] "2026-01-05" "2026-01-12")))))
