(ns tunarr.scheduler.scheduling.plans
  "Read-side assembly over the scheduling storage: calendar helpers, the weekly
   schedule preview (expand the stored grid + overrides — no Tunabrain call), and
   a combined per-channel dashboard view for the UI.

   These never gate or mutate the plan; they surface what is (or would be) on
   air, plus the operator's guidance."
  (:require [tunarr.scheduler.scheduling.storage :as storage]
            [tunarr.scheduler.scheduling.expander :as expander])
  (:import [java.time LocalDate ZoneId]
           [java.time.format DateTimeFormatter]))

(def ^:private month-fmt (DateTimeFormatter/ofPattern "yyyy-MM"))

(defn- ->date ^LocalDate [d]
  (if (instance? LocalDate d) d (LocalDate/parse (str d))))

(defn quarter-of
  "The quarter label (\"Q1\"..\"Q4\") for a date."
  [date]
  (str "Q" (inc (quot (dec (.getMonthValue (->date date))) 3))))

(defn year-of [date] (.getYear (->date date)))

(defn quarter-range
  "Half-open [start, end) LocalDates spanning a quarter (\"Q1\"..\"Q4\") of a
   year — the natural feasibility/expansion horizon for a quarterly grid."
  [quarter year]
  (let [q     (Integer/parseInt (subs quarter 1))
        start (LocalDate/of (int year) (inc (* 3 (dec q))) 1)]
    [start (.plusMonths start 3)]))

(defn month-of
  "The \"YYYY-MM\" key for a date."
  [date]
  (.format (->date date) month-fmt))

(defn months-in-range
  "Distinct \"YYYY-MM\" keys touched by the half-open range [start, end)."
  [start end]
  (let [e (->date end)]
    (->> (iterate #(.plusDays ^LocalDate % 1) (->date start))
         (take-while #(.isBefore ^LocalDate % e))
         (map month-of)
         distinct
         vec)))

(defn today
  "Today's calendar date, resolved in the JVM's default zone — which is set from
   the `TZ` environment variable. This is the zone the weekly/monthly/quarterly
   windows and the naive DailySlot wall-clock times are anchored in, and
   Pseudovision interprets those naive times in *its* `TZ`, so the scheduler and
   Pseudovision MUST run with the same `TZ` for the days/times to line up. The
   zone is named explicitly (rather than the implicit `LocalDate/now`) so this
   dependency is visible; pass a `ZoneId` to override."
  ([] (today (ZoneId/systemDefault)))
  ([^ZoneId zone] (LocalDate/now zone)))

;; ---------------------------------------------------------------------------
;; Reads
;; ---------------------------------------------------------------------------

(defn preview
  "Expand the channel's current frozen grid + the active overrides for every
   month the window touches, over [start, end). Returns {:channel :start :end
   :grid_id :slots} (slots is a possibly-empty DailySlot vector); :grid_id is nil
   and :slots empty when no grid is frozen for the window's quarter."
  [ex channel start end]
  (let [start  (->date start)
        end    (->date end)
        record (storage/current-grid ex channel (quarter-of start) (year-of start))
        ovr    (->> (months-in-range start end)
                    (mapcat (fn [m] (:overrides (storage/current-overrides ex channel m))))
                    vec)
        slots  (if record
                 (expander/expand (:grid record) ovr start end)
                 [])]
    {:channel channel
     :start   (str start)
     :end     (str end)
     :grid_id (:grid_id record)
     :slots   slots}))

(defn dashboard
  "A combined per-channel view for the UI: the current frozen grid (with its
   feasibility snapshot), the current month's overrides, and the operator
   guidance. `as-of` defaults to today."
  ([ex channel] (dashboard ex channel (today)))
  ([ex channel as-of]
   (let [d    (->date as-of)
         grid (storage/current-grid ex channel (quarter-of d) (year-of d))
         ovr  (storage/current-overrides ex channel (month-of d))]
     {:channel   channel
      :quarter   (quarter-of d)
      :year      (year-of d)
      :month     (month-of d)
      :grid      grid
      :overrides ovr
      :guidance  (storage/get-guidance ex channel)})))
