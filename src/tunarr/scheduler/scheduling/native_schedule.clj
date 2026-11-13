(ns tunarr.scheduler.scheduling.native-schedule
  "Translates a frozen layered Grid (contracts/Grid) into Pseudovision's
   NATIVE schedule/slot model, so PV's own playout engine — block/count/flood
   fill modes, per-collection enumerators that persist position across
   airings (sequential series resume where they left off), and variety-aware
   filler bin-packing — programs the channel directly, instead of Tunarr
   Scheduler pre-resolving a flat DailySlot stream one item at a time (see
   `scheduling.expander`, now retired from the hot path for base grids).

   Each GridStrip becomes exactly one PV schedule_slot: a 'fixed' anchor at
   the strip's start time, gated by a `days_of_week` bitmask, with
   `fill_mode = block` so PV packs the block with as many sequential items as
   fit (padding any remainder with filler) instead of playing one item and
   leaving dead air.

   Content sourcing is split in two because PV collections are server-side
   entities with ids that must be created/looked-up before a slot can
   reference them:
     1. `content-sources` (pure) — the distinct PV \"smart\" collection specs
        this grid's strips need (a named series' episode list, or a
        channel-scoped genre pool).
     2. `->schedule` (pure) — given those specs already resolved to
        collection ids (`source-id-by-name`, built by the orchestration layer
        via `ensure-collection!`), emits the final slot attrs for
        `backends.pseudovision.client/add-slot!`.

   A `movie:<id>` or `program:<id>` strip with a numeric id needs no
   collection: the slot points `media-item-id` straight at the item. The
   catalog profile names Jellyfin titles by their remote key (a GUID), not by
   a numeric id, so such a strip gets a one-item smart collection
   (`auto:item:<key>`, `{:item-ref key}`), and a series strip's collection
   looks the show up by `{:show-id key}`. Pseudovision resolves either form."
  (:require [clojure.string :as str]
            [tunarr.scheduler.scheduling.calendar :as cal]))

;; ---------------------------------------------------------------------------
;; Small pure helpers
;; ---------------------------------------------------------------------------

(defn- parse-mins [hhmm]
  (let [[h m] (map parse-long (str/split hhmm #":"))]
    (+ (* 60 h) m)))

(defn- strip-duration-minutes
  "A strip's own wall-clock length in minutes. `end <= start` wraps past
   midnight — same convention as scheduling.candidates/feasibility."
  [strip]
  (let [s (parse-mins (:start strip)) e (parse-mins (:end strip))]
    (if (> e s) (- e s) (+ (- 1440 s) e))))

(defn- iso8601-duration
  "ISO-8601 duration string for `minutes`, e.g. 90 -> \"PT1H30M\". PV's
   `create-slot!` coerces this via sql-util/->pg-interval."
  [minutes]
  (let [h (quot minutes 60) m (mod minutes 60)]
    (str "PT" (when (pos? h) (str h "H")) (when (pos? m) (str m "M"))
         (when (and (zero? h) (zero? m)) "0M"))))

(defn- days->bitmask
  "Bitmask for a DaysPattern: bit i (value 2^i) set for weekday-codes[i],
   matching schedule_slots.days_of_week's convention (Mon=1 Tue=2 Wed=4 ...
   Sun=64; migration 20260428001-add-days-of-week-to-slots)."
  [days-pattern]
  (reduce (fn [acc code]
            (let [i (.indexOf ^java.util.List cal/weekday-codes code)]
              (if (neg? i) acc (bit-or acc (bit-shift-left 1 i)))))
          0
          (cal/pattern->codes days-pattern)))

(defn- media-kind [media-id] (first (str/split (str media-id) #":" 2)))
(defn- media-arg [media-id] (second (str/split (str media-id) #":" 2)))

(defn- parse-long-safe [s]
  (try (Long/parseLong (str s)) (catch Exception _ nil)))

(defn- distinct-by-name [specs]
  (vals (reduce (fn [acc s] (assoc acc (:name s) s)) (array-map) specs)))

;; ---------------------------------------------------------------------------
;; Content sources — the PV "smart" collections this grid needs
;; ---------------------------------------------------------------------------

(defn- series-source-name [arg] (str "auto:series:" arg))
(defn- item-source-name [arg] (str "auto:item:" arg))

(defn- single-item-kind? [kind] (contains? #{"movie" "program"} kind))

(defn- category-source-name [category channel-tag]
  (str "auto:category:" (str/lower-case category) ":" channel-tag))

(defn- content-source
  "The collection spec `strip` needs, or nil when its content needs none
   (a `movie:<id>`/`program:<id>` strip with a numeric id plays the item
   directly). `channel-tag` (e.g.
   \"channel:hua\") scopes a category pool to this channel's own mapped
   media — see pseudovision.db.media/resolve-playable-by-tag's :require-tags."
  [strip channel-tag]
  (let [media-id (-> strip :content :media_id)
        kind     (media-kind media-id)
        arg      (media-arg media-id)]
    (case kind
      "series" (when-not (str/blank? arg)
                 {:name (series-source-name arg) :kind :show
                  :show-id (or (parse-long-safe arg) arg)})
      ("movie" "program") (when (and (not (str/blank? arg)) (nil? (parse-long-safe arg)))
                            {:name (item-source-name arg) :kind :item :item-ref arg})
      "random" {:name (category-source-name arg channel-tag) :kind :category
                :category arg :channel-tag channel-tag}
      nil)))

(declare override->strip)

(defn content-sources
  "Distinct collection specs (deduped by :name) `grid`'s strips, and any
   `overrides` (see `->override-slot`), will need resolved (created-or-found)
   before `->schedule` can build slot attrs.
   Each spec: `{:name .. :kind :show :show-id ..}`,
   `{:name .. :kind :item :item-ref ..}` or
   `{:name .. :kind :category :category .. :channel-tag ..}`."
  ([grid channel-tag] (content-sources grid channel-tag nil))
  ([grid channel-tag overrides]
   (->> (concat (:strips grid) (map (comp override->strip :override) overrides))
        (keep #(content-source % channel-tag))
        distinct-by-name)))

;; ---------------------------------------------------------------------------
;; Slot construction
;; ---------------------------------------------------------------------------

(defn- slot-timing
  "The anchor/fill-mode fields shared by every content kind: fixed at the
   strip's start, gated by its day pattern, block-filled for its duration,
   padding any remainder with filler rather than leaving dead air."
  [strip]
  {:anchor "fixed"
   :start-time (str (:start strip) ":00")
   :days-of-week (days->bitmask (:days strip))
   :fill-mode "block"
   :block-duration (iso8601-duration (strip-duration-minutes strip))
   :tail-mode "filler"})

(defn ->slot
  "One GridStrip -> PV schedule-slot attrs (for
   backends.pseudovision.client/add-slot!), or `{:error .. :strip-id ..}`
   when its content can't be resolved (unknown media_id kind, an unresolved
   collection, or an unparsable movie id). `source-id-by-name` maps a
   `content-sources` spec's :name to its resolved PV collection id."
  [strip channel-tag source-id-by-name]
  (let [content  (:content strip)
        media-id (:media_id content)
        kind     (media-kind media-id)
        arg      (media-arg media-id)
        strategy (:strategy content "sequential")
        timing   (slot-timing strip)
        base     (merge timing {:custom-title (:label content)})]
    (case kind
      "series"
      (let [coll-id (get source-id-by-name (series-source-name arg))]
        (if coll-id
          (merge base {:collection-id coll-id
                       :playback-order (if (= strategy "random") "random" "chronological")})
          {:error (str "no resolved collection for series " arg) :strip-id (:strip_id strip)}))

      ("movie" "program")
      (if-let [item-id (parse-long-safe arg)]
        (merge base {:media-item-id item-id :playback-order "chronological"})
        (if-let [coll-id (get source-id-by-name (item-source-name arg))]
          (merge base {:collection-id coll-id :playback-order "chronological"})
          {:error (str "no resolved collection for " kind " " arg) :strip-id (:strip_id strip)}))

      "random"
      (let [src-name (category-source-name arg channel-tag)
            coll-id  (get source-id-by-name src-name)]
        (if coll-id
          (merge base {:collection-id coll-id :playback-order "random"})
          {:error (str "no resolved collection for category " arg) :strip-id (:strip_id strip)}))

      {:error (str "unrecognized media_id kind '" kind "'") :strip-id (:strip_id strip)})))

;; ---------------------------------------------------------------------------
;; Monthly overrides -> date-bounded override slots
;; ---------------------------------------------------------------------------

(defn- override->strip
  "An Override's scheduling fields in GridStrip shape, so it reuses `->slot`."
  [override]
  {:strip_id (:override_id override)
   :days     (or (get-in override [:scope :days]) "daily")
   :start    (:start override)
   :end      (:end override)
   :content  (:content override)})

(defn- max-date [a b] (if (pos? (compare a b)) a b))
(defn- min-date [a b] (if (neg? (compare a b)) a b))

(defn override-date-range
  "Inclusive [active-from active-until] ISO dates for `override`, stored for
   calendar month `month` (\"YYYY-MM\"): its single `:date`, or its recurring
   window clamped to the month. nil when the window misses the month."
  [override month]
  (let [scope (:scope override)
        first-day (java.time.LocalDate/parse (str month "-01"))
        m-from (str first-day)
        m-until (str (.minusDays (.plusMonths first-day 1) 1))
        [from until] (if-let [d (:date scope)]
                       [d d]
                       [(max-date m-from (or (:effective_start scope) m-from))
                        (min-date m-until (or (:effective_end scope) m-until))])]
    (when-not (pos? (compare from until))
      [from until])))

(defn ->override-slot
  "One stored Override (`{:month \"YYYY-MM\" :override Override}`) -> PV slot
   attrs: built like a strip, then bounded to its dates and flagged
   `is-override` so PV plays it in place of the base strips it overlaps.
   Returns `{:error ..}` like `->slot`, or nil when the window misses the month."
  [{:keys [month override]} channel-tag source-id-by-name]
  (when-let [[from until] (override-date-range override month)]
    (let [slot (->slot (override->strip override) channel-tag source-id-by-name)]
      (if (:error slot)
        slot
        (assoc slot :active-from from :active-until until :is-override true)))))

(defn- bound-slot
  "`slot` limited to dates in [from, until] (inclusive ISO dates; nil leaves
   that side open). An error map passes through."
  [slot from until]
  (if (:error slot)
    slot
    (cond-> slot
      from  (assoc :active-from from)
      until (assoc :active-until until))))

(defn ->schedule
  "Grid (+ optional monthly `:overrides`, each `{:month :override}`) ->
   `{:name :slots :warnings}` for backends.pseudovision.client/create-schedule!
   + add-slot!. Strips are ordered by start time (ascending), followed by the
   override slots; PV builds all-fixed schedules day by day, so list order only
   keeps a hand-inspected schedule legible. Strips or overrides that fail to
   resolve are dropped with a warning rather than failing the whole sync.

   `:next-grid` with `:next-from` (an ISO date, the next quarter's first day)
   adds the next quarter's strips as slots that start airing on that date, and
   ends `grid`'s strips the day before. Syncing the next quarter ahead of time
   this way switches the channel over exactly on the boundary, and lets the
   first month's overrides reach PV before that month starts."
  [grid channel-tag source-id-by-name & {:keys [schedule-name overrides next-grid next-from]}]
  (let [order  (partial sort-by (comp parse-mins :start))
        until  (when (and next-grid next-from)
                 (str (.minusDays (java.time.LocalDate/parse (str next-from)) 1)))
        base   (mapv #(bound-slot (->slot % channel-tag source-id-by-name) nil until)
                     (order (:strips grid)))
        nxt    (when until
                 (mapv #(bound-slot (->slot % channel-tag source-id-by-name) (str next-from) nil)
                       (order (:strips next-grid))))
        built  (into (into base nxt)
                     (keep #(->override-slot % channel-tag source-id-by-name))
                     (sort-by (fn [{:keys [month override]}]
                                [(first (override-date-range override month))
                                 (parse-mins (:start override))])
                              overrides))
        ok     (remove :error built)
        errs   (filter :error built)
        slots  (into [] (map-indexed (fn [i s] (assoc s :slot-index i))) ok)]
    {:name (or schedule-name (str (:channel grid) " (auto)"))
     :slots slots
     :warnings (mapv :error errs)}))
