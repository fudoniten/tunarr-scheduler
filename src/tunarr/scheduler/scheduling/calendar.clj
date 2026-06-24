(ns tunarr.scheduler.scheduling.calendar
  "Pure day-of-week helpers shared by the grid expander and feasibility checker.

   Weekday codes are the lower-case three-letter forms used throughout the grid
   contracts (\"mon\" … \"sun\"), with Monday as the canonical week start —
   matching tunabrain's DayOfWeek / DayPattern."
  (:require [clojure.string :as str])
  (:import [java.time LocalDate]))

(def weekday-codes
  "Index 0 = Monday … 6 = Sunday (aligns with java.time DayOfWeek value − 1 and
   Python's date.weekday())."
  ["mon" "tue" "wed" "thu" "fri" "sat" "sun"])

(defn ->code
  "Three-letter weekday code for a LocalDate."
  [^LocalDate d]
  (nth weekday-codes (dec (.getValue (.getDayOfWeek d)))))

(defn pattern->codes
  "The set of weekday codes a days-pattern covers: \"daily\", \"weekdays\"
   (mon-fri), \"weekends\" (sat-sun), or an explicit list like [\"mon\" \"wed\"]."
  [pattern]
  (cond
    (= pattern "daily")    (set weekday-codes)
    (= pattern "weekdays") #{"mon" "tue" "wed" "thu" "fri"}
    (= pattern "weekends") #{"sat" "sun"}
    (sequential? pattern)  (set pattern)
    :else                  #{}))

(defn matches?
  "Whether a days-pattern applies to a weekday code."
  [pattern code]
  (contains? (pattern->codes pattern) code))

(defn date-matches?
  "Whether a days-pattern applies to a LocalDate."
  [pattern ^LocalDate d]
  (matches? pattern (->code d)))

(defn specificity
  "Scope-specificity rank of a recurring day-pattern (higher = more specific).
   A specific calendar date (rank 3) is handled by callers; this covers the
   recurring patterns: explicit list = 2, named group = 1, daily = 0."
  [pattern]
  (cond
    (sequential? pattern)              2
    (#{"weekdays" "weekends"} pattern) 1
    :else                              0))

(defn render-days
  "Human-readable rendering of a set of weekday codes, collapsing to the named
   groups where possible (for feasibility messages)."
  [codes]
  (let [s (set codes)]
    (cond
      (= s (set weekday-codes))            "daily"
      (= s #{"mon" "tue" "wed" "thu" "fri"}) "weekdays"
      (= s #{"sat" "sun"})                 "weekends"
      :else (->> weekday-codes (filter s) (str/join ",")))))
