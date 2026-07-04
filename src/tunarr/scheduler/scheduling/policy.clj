(ns tunarr.scheduler.scheduling.policy
  "Deterministic enforcement of per-channel content policy (contracts/ContentPolicy).

   A policy is a HARD constraint (unlike operator guidance, which only steers the
   LLM). The current constraint kind is the **watershed**: content carrying a
   dimension value (e.g. `audience:adult`) may air only within an allowed
   time-of-day window; outside it the content is forbidden.

   Everything here is pure — no I/O, no clock reads — so it is unit-testable and
   safe to run in both the feasibility checker and the publish path:

   • `grid-violations` — given a grid, a media→tags resolver, and a policy,
     return human-readable strings for every strip that places restricted
     content in a forbidden part of the day. The feasibility checker turns these
     into a blocking finding so the repair loop re-places the strip.
   • `enforce-slots` — given expander output (DailySlots), a policy, and the
     grid's default content, substitute the default for the forbidden portion of
     any restricted slot (splitting the slot at the watershed boundary). The
     publish step runs this as an air-time backstop.

   \"Restricted\" is detected from tags the scheduler can see: a strip/slot's own
   `category_filters`, plus (in the feasibility path) the resolved catalog tags
   of its `media_id`. A watershed's tag is `<dimension>:<value>` — the same
   `dimension:value` convention Pseudovision uses for `channel:`/`genre:` tags.
   Broad `random:` pools whose individual items are adult but whose strip is not
   tagged cannot be caught deterministically here; that needs an excluded-tags
   field on the DailySlot contract (a cross-repo change), noted in the ROADMAP."
  (:require [clojure.string :as str])
  (:import [java.time LocalDateTime LocalTime]
           [java.time.format DateTimeFormatter]))

(def ^:private slot-formatter
  (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss"))

;; ---------------------------------------------------------------------------
;; Tags + matching
;; ---------------------------------------------------------------------------

(defn watershed-tag
  "The catalog tag a watershed restricts, `<dimension>:<value>` (lower-cased)."
  [{:keys [dimension value]}]
  (str/lower-case (str dimension ":" value)))

(defn- normalize [tags]
  (into #{} (comp (remove nil?) (map (comp str/lower-case str))) tags))

(defn restricted?
  "Whether `tags` (a seq of tag strings) brings content under `watershed`.
   Matches the full `dimension:value` tag or, as a fallback, the bare value —
   so a catalog that stores audience as `adult` rather than `audience:adult`
   still trips the guard."
  [watershed tags]
  (let [ts (normalize tags)]
    (or (contains? ts (watershed-tag watershed))
        (contains? ts (str/lower-case (str (:value watershed)))))))

;; ---------------------------------------------------------------------------
;; Time-of-day windows (minutes since midnight, [lo hi))
;; ---------------------------------------------------------------------------

(defn- parse-mins [hhmm]
  (let [[h m] (map parse-long (str/split hhmm #":"))]
    (+ (* 60 h) m)))

(defn- day-spans
  "The on-air sub-intervals of [start, end) within one 24h cycle, as [lo hi)
   minute pairs. `end <= start` wraps past midnight into two spans."
  [start-min end-min]
  (if (> end-min start-min)
    [[start-min end-min]]
    [[start-min 1440] [0 end-min]]))

(defn- allowed-spans
  "Minute spans a watershed permits within a day."
  [watershed]
  (day-spans (parse-mins (:allowed_from watershed))
             (parse-mins (:allowed_to watershed))))

(defn- forbidden-spans
  "Complement of the allowed spans within [0, 1440) — when a watershed's content
   may NOT air."
  [watershed]
  (let [allowed (sort-by first (allowed-spans watershed))]
    (loop [cursor 0, remaining allowed, gaps []]
      (if-let [[lo hi] (first remaining)]
        (recur (max cursor hi) (rest remaining)
               (if (< cursor lo) (conj gaps [cursor lo]) gaps))
        (if (< cursor 1440) (conj gaps [cursor 1440]) gaps)))))

(defn- spans-intersect?
  [a-spans b-spans]
  (boolean
   (some (fn [[al ah]]
           (some (fn [[bl bh]] (< (max al bl) (min ah bh))) b-spans))
         a-spans)))

;; ---------------------------------------------------------------------------
;; Grid-level violations (feasibility path)
;; ---------------------------------------------------------------------------

(defn- strip-tags
  "All tags a strip's content is known to carry: its own category_filters plus
   the catalog tags of its media_id (via `resolve-tags`, which may return nil)."
  [resolve-tags strip]
  (let [content (:content strip)]
    (concat (:category_filters content)
            (resolve-tags (:media_id content)))))

(defn- render-days [days]
  (cond
    (string? days) days
    (sequential? days) (str/join "/" (map name days))
    :else (str days)))

(defn grid-violations
  "Human-readable strings for every (strip, watershed) pair where the strip airs
   restricted content during a forbidden part of the day. `resolve-tags` maps a
   media_id to a seq of catalog tags (or nil); pass `(constantly nil)` when no
   catalog is available and only `category_filters` should be consulted."
  [grid policy resolve-tags]
  (let [watersheds (:watersheds policy)]
    (vec
     (for [strip (:strips grid)
           ws    watersheds
           :let  [tags (strip-tags resolve-tags strip)]
           :when (restricted? ws tags)
           :let  [air (day-spans (parse-mins (:start strip)) (parse-mins (:end strip)))]
           :when (spans-intersect? air (forbidden-spans ws))]
       (format "strip '%s' airs %s content (%s) on %s %s-%s, but it is only permitted %s-%s"
               (:strip_id strip)
               (:value ws) (watershed-tag ws)
               (render-days (:days strip)) (:start strip) (:end strip)
               (:allowed_from ws) (:allowed_to ws))))))

;; ---------------------------------------------------------------------------
;; Slot-level enforcement (publish path)
;; ---------------------------------------------------------------------------

(defn- ->dt ^LocalDateTime [s] (LocalDateTime/parse s))

(defn- fmt [^LocalDateTime dt] (.format dt slot-formatter))

(defn- allowed-abs-intervals
  "Absolute [start end) datetimes during which `watershed` permits content, that
   overlap the slot window [s, e). Built by projecting the daily allowed window
   onto each date the slot touches (plus the prior day, so an overnight window
   can cover an early-morning slot start)."
  [watershed ^LocalDateTime s ^LocalDateTime e]
  (let [from (LocalTime/parse (:allowed_from watershed))
        to   (LocalTime/parse (:allowed_to watershed))
        wrap (not (.isAfter to from))            ; crosses midnight
        d0   (.minusDays (.toLocalDate s) 1)
        d1   (.toLocalDate e)]
    (loop [d d0, acc []]
      (if (.isAfter d d1)
        acc
        (let [start (LocalDateTime/of d from)
              end   (LocalDateTime/of (if wrap (.plusDays d 1) d) to)
              lo    (if (.isAfter start s) start s)
              hi    (if (.isBefore end e) end e)]
          (recur (.plusDays d 1)
                 (if (.isBefore lo hi) (conj acc [lo hi]) acc)))))))

(defn- subtract-intervals
  "[s, e) minus a set of allowed [lo hi) intervals ⇒ the forbidden sub-intervals."
  [^LocalDateTime s ^LocalDateTime e allowed]
  (let [sorted (sort-by first allowed)]
    (loop [cursor s, remaining sorted, gaps []]
      (if-let [[lo hi] (first remaining)]
        (recur (if (.isAfter hi cursor) hi cursor)
               (rest remaining)
               (if (.isBefore cursor lo) (conj gaps [cursor lo]) gaps))
        (if (.isBefore cursor e) (conj gaps [cursor e]) gaps)))))

(defn- slot-with-content
  "A slot map [start end) carrying `content`'s fields (mirrors the expander's
   segment->slot so substituted segments are contract-conformant DailySlots)."
  [^LocalDateTime start ^LocalDateTime end content]
  {:start_time (fmt start)
   :end_time   (fmt end)
   :media_id   (:media_id content)
   :media_selection_strategy (:strategy content "sequential")
   :category_filters (vec (:category_filters content))
   :notes      (vec (:notes content))})

(defn- slot->content
  "Reconstruct a Content map from a DailySlot's own fields, so a kept segment
   round-trips into an identical slot."
  [slot]
  {:media_id (:media_id slot)
   :strategy (:media_selection_strategy slot)
   :category_filters (:category_filters slot)
   :notes (:notes slot)})

(defn- enforce-slot
  "Split one slot against the policy. If the slot's content is restricted by any
   watershed, the parts of its window in that watershed's forbidden time become
   `default-content` (dropped entirely when there is no default); the rest is
   kept. Non-restricted slots pass through unchanged."
  [policy default-content slot]
  (let [tags   (:category_filters slot)
        active (filter #(restricted? % tags) (:watersheds policy))]
    (if (empty? active)
      [slot]
      (let [s (->dt (:start_time slot))
            e (->dt (:end_time slot))
            content (slot->content slot)
            ;; Intersection of every active watershed's allowed intervals: the
            ;; content may air only where ALL of them agree it may.
            allowed (reduce
                     (fn [acc ws]
                       (for [[al ah] acc
                             [bl bh] (allowed-abs-intervals ws s e)
                             :let [lo (if (.isAfter al bl) al bl)
                                   hi (if (.isBefore ah bh) ah bh)]
                             :when (.isBefore lo hi)]
                         [lo hi]))
                     [[s e]]
                     active)
            forbidden (subtract-intervals s e allowed)
            kept      (map (fn [[lo hi]] [lo hi content]) allowed)
            filled    (when default-content
                        (map (fn [[lo hi]] [lo hi default-content]) forbidden))]
        (->> (concat kept filled)
             (sort-by (fn [[lo _ _]] lo))
             (map (fn [[lo hi c]] (slot-with-content lo hi c)))
             vec)))))

(defn enforce-slots
  "Apply `policy` to a vector of DailySlots (expander output), substituting
   `default-content` for the forbidden portion of any restricted slot. Returns a
   new, sorted DailySlot vector. A nil/empty policy is a pass-through.

   Detection uses each slot's `category_filters` (the only per-slot signal, since
   Pseudovision resolves `media_id` → episode only at air time)."
  [slots policy default-content]
  (if (empty? (:watersheds policy))
    (vec slots)
    (->> slots
         (mapcat #(enforce-slot policy default-content %))
         (sort-by :start_time)
         vec)))
