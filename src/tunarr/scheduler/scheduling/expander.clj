(ns tunarr.scheduler.scheduling.expander
  "Deterministic, LLM-free projection of a frozen Grid + sparse Overrides onto a
   concrete date range, producing DailySlots.

   `expand` is a PURE function: no I/O, no randomness, no clock reads. The same
   (grid, overrides, range) always yields the same slots. Episode rotation is
   NOT decided here — it happens in Pseudovision at air time via each slot's
   `media_selection_strategy`.

   Algorithm (handoff spec §4.2; full prose in tunabrain
   docs/scheduling-grid-spec.md §6):

     1. Materialize  — for each strip/override, for each matching calendar date
        in [range_start − 1 day, range_end) (the leading day lets an overnight
        strip from the prior day cover the early hours of range_start), compute
        the absolute [start, end) datetime interval (end <= start ⇒ crosses
        midnight, add a day) and a precedence tuple.
     2. Sweep        — between consecutive interval boundaries, the
        highest-precedence candidate that *fully covers* the elementary interval
        wins; otherwise fill with default_content (or leave a gap).
     3. Merge        — adjacent elementary intervals won by the same rule (by
        rule_id, or both by default) are coalesced.
     4. Emit         — DailySlots sorted by start, clipped to
        [range_start, range_end).

   Precedence tuple (compared lexicographically, higher wins):
     [layer_rank scope_specificity priority definition_order]
       layer_rank        base grid strip = 0, override = 1
       scope_specificity specific date = 3, explicit weekday list = 2,
                         named group (weekdays/weekends) = 1, daily = 0
       priority          the integer field on the rule
       definition_order  materialization index (later wins ties)

   NOTE: tunabrain's tests/test_grid_expander.py is the golden conformance
   suite. The tests alongside this namespace reproduce its enumerated cases from
   the spec prose; the upstream file should still be ported verbatim once
   accessible, to catch any case not covered here."
  (:require [tunarr.scheduler.scheduling.contracts :as contracts]
            [tunarr.scheduler.scheduling.calendar :as cal])
  (:import [java.time LocalDate LocalTime LocalDateTime]
           [java.time.format DateTimeFormatter]))

;; ---------------------------------------------------------------------------
;; Date / time helpers
;; ---------------------------------------------------------------------------

(def ^:private slot-formatter
  ;; DailySlot wire format: "YYYY-MM-DDTHH:MM:SS" (seconds always present).
  (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss"))

(defn- ->date ^LocalDate [d]
  (if (instance? LocalDate d) d (LocalDate/parse (str d))))

(defn- abs-interval
  "Absolute [start end) datetimes for a rule firing on date `d`. `end <= start`
   (including equal, i.e. a full 24h span) means the interval crosses midnight."
  [^LocalDate d start-str end-str]
  (let [st  (LocalTime/parse start-str)
        et  (LocalTime/parse end-str)
        end-date (if (.isAfter et st) d (.plusDays d 1))]
    [(LocalDateTime/of d st) (LocalDateTime/of end-date et)]))

;; ---------------------------------------------------------------------------
;; Materialization
;; ---------------------------------------------------------------------------

(defn- strip-candidates [strips dates]
  (for [strip strips
        ^LocalDate d dates
        :when (cal/date-matches? (:days strip) d)]
    (let [[s e] (abs-interval d (:start strip) (:end strip))]
      {:start s :end e :content (:content strip)
       :layer 0 :spec (cal/specificity (:days strip)) :priority (:priority strip 0)
       :rule (:strip_id strip)})))

(defn- override-matches?
  "Whether an override scope fires on date `d`. Recurring window bounds are
   optional — absent `effective_start`/`effective_end` means unbounded."
  [scope ^LocalDate d]
  (if (:date scope)
    (= d (->date (:date scope)))
    (and (cal/date-matches? (:days scope) d)
         (or (nil? (:effective_start scope))
             (not (.isBefore d (->date (:effective_start scope)))))
         (or (nil? (:effective_end scope))
             (not (.isAfter d (->date (:effective_end scope))))))))

(defn- override-candidates [overrides dates]
  (for [ovr overrides
        ^LocalDate d dates
        :let [scope (:scope ovr)]
        :when (override-matches? scope d)]
    (let [[s e] (abs-interval d (:start ovr) (:end ovr))
          spec  (if (:date scope) 3 (cal/specificity (:days scope)))]
      {:start s :end e :content (:content ovr)
       :layer 1 :spec spec :priority (:priority ovr 0)
       :rule (:override_id ovr)})))

(defn- materialize
  "All candidate intervals with precedence tuples. `definition_order` is the
   index in the concatenated (strips-then-overrides) sequence, so later-defined
   rules win ties — and within a layer, overrides outrank strips by layer_rank
   regardless of order."
  [grid overrides dates]
  (->> (concat (strip-candidates (:strips grid) dates)
               (override-candidates overrides dates))
       (map-indexed (fn [i c]
                      (assoc c :prec [(:layer c) (:spec c) (:priority c) i])))
       vec))

;; ---------------------------------------------------------------------------
;; Sweep + merge
;; ---------------------------------------------------------------------------

(defn- covers? [c a b]
  (and (not (.isAfter ^LocalDateTime (:start c) a))
       (not (.isBefore ^LocalDateTime (:end c) b))))

(defn- winner
  "Highest-precedence candidate fully covering [a b), or nil."
  [candidates a b]
  (reduce (fn [best c]
            (if (covers? c a b)
              (if (or (nil? best) (pos? (compare (:prec c) (:prec best)))) c best)
              best))
          nil
          candidates))

(defn- boundaries
  "Sorted, de-duped boundary datetimes within [lo hi] (inclusive), drawn from
   every candidate edge plus the window bounds."
  [candidates ^LocalDateTime lo ^LocalDateTime hi]
  (->> (mapcat (fn [c] [(:start c) (:end c)]) candidates)
       (concat [lo hi])
       (filter (fn [^LocalDateTime dt] (and (not (.isBefore dt lo))
                                            (not (.isAfter dt hi)))))
       distinct
       (sort)
       vec))

(defn- segments
  "Per elementary interval, the winning candidate's content (or default). Gaps
   (no winner, no default) are dropped. Each kept segment carries a merge `:key`
   — the winner's `rule_id`, or `::default` — so adjacent intervals won by the
   same rule coalesce (matching the reference expander; an all-day daily strip
   thus collapses across midnight)."
  [candidates default lo hi]
  (for [[a b] (partition 2 1 (boundaries candidates lo hi))
        :let [w (winner candidates a b)
              content (if w (:content w) default)]
        :when content]
    {:start a :end b :content content :key (if w (:rule w) ::default)}))

(defn- merge-adjacent [segs]
  (reduce (fn [acc seg]
            (let [prev (peek acc)]
              (if (and prev
                       (= (:key prev) (:key seg))
                       (= (:end prev) (:start seg)))
                (conj (pop acc) (assoc prev :end (:end seg)))
                (conj acc seg))))
          []
          segs))

;; ---------------------------------------------------------------------------
;; Emit
;; ---------------------------------------------------------------------------

(defn- segment->slot [seg]
  (let [c (:content seg)]
    {:start_time (.format ^LocalDateTime (:start seg) slot-formatter)
     :end_time   (.format ^LocalDateTime (:end seg) slot-formatter)
     :media_id   (:media_id c)
     ;; Content fields carry Pydantic defaults; mirror them so a hand-authored
     ;; grid that omits them still produces conformant slots.
     :media_selection_strategy (:strategy c "sequential")
     :category_filters (vec (:category_filters c))
     :notes      (vec (:notes c))}))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn expand
  "Project `grid` + `overrides` onto [range-start, range-end) → vector of
   DailySlots (see contracts/DailySlot), sorted by start.

   `range-start`/`range-end` accept a LocalDate or an ISO date string; the range
   is half-open (range-end exclusive). `overrides` may be nil."
  [grid overrides range-start range-end]
  (let [rs (->date range-start)
        re (->date range-end)
        lo (LocalDateTime/of rs LocalTime/MIDNIGHT)
        hi (LocalDateTime/of re LocalTime/MIDNIGHT)
        ;; Lead day: range-start − 1, so a prior-day overnight strip can cover
        ;; the early hours of range-start.
        dates (->> (iterate #(.plusDays ^LocalDate % 1) (.minusDays rs 1))
                   (take-while #(.isBefore ^LocalDate % re))
                   vec)
        candidates (materialize grid (or overrides []) dates)]
    (->> (segments candidates (:default_content grid) lo hi)
         merge-adjacent
         (mapv segment->slot))))
