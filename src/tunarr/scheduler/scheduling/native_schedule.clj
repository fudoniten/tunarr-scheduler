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

   A `movie:<id>` strip needs no collection: the slot points `media-item-id`
   straight at the movie."
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

(defn- category-source-name [category channel-tag]
  (str "auto:category:" (str/lower-case category) ":" channel-tag))

(defn- content-source
  "The collection spec `strip` needs, or nil when its content needs none
   (a `movie:<id>` strip plays the item directly). `channel-tag` (e.g.
   \"channel:hua\") scopes a category pool to this channel's own mapped
   media — see pseudovision.db.media/resolve-playable-by-tag's :require-tags."
  [strip channel-tag]
  (let [media-id (-> strip :content :media_id)
        kind     (media-kind media-id)
        arg      (media-arg media-id)]
    (case kind
      "series" (when-let [show-id (parse-long-safe arg)]
                 {:name (str "auto:series:" show-id) :kind :show :show-id show-id})
      "random" {:name (category-source-name arg channel-tag) :kind :category
                :category arg :channel-tag channel-tag}
      nil)))

(defn content-sources
  "Distinct collection specs (deduped by :name) `grid`'s strips will need
   resolved (created-or-found) before `->schedule` can build slot attrs.
   Each spec: `{:name .. :kind :show :show-id ..}` or
   `{:name .. :kind :category :category .. :channel-tag ..}`."
  [grid channel-tag]
  (->> (:strips grid)
       (keep #(content-source % channel-tag))
       distinct-by-name))

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
      (let [src-name (some->> (parse-long-safe arg) (str "auto:series:"))
            coll-id  (get source-id-by-name src-name)]
        (if coll-id
          (merge base {:collection-id coll-id
                       :playback-order (if (= strategy "random") "random" "chronological")})
          {:error (str "no resolved collection for series " arg) :strip-id (:strip_id strip)}))

      "movie"
      (if-let [item-id (parse-long-safe arg)]
        (merge base {:media-item-id item-id :playback-order "chronological"})
        {:error (str "unparsable movie id " arg) :strip-id (:strip_id strip)})

      "random"
      (let [src-name (category-source-name arg channel-tag)
            coll-id  (get source-id-by-name src-name)]
        (if coll-id
          (merge base {:collection-id coll-id :playback-order "random"})
          {:error (str "no resolved collection for category " arg) :strip-id (:strip_id strip)}))

      {:error (str "unrecognized media_id kind '" kind "'") :strip-id (:strip_id strip)})))

(defn ->schedule
  "Grid -> `{:name :slots :warnings}` for
   backends.pseudovision.client/create-schedule! + add-slot!. Strips are
   ordered by start time (ascending) — PV's engine self-corrects each fixed
   slot's next occurrence regardless of list position, but a chronological
   read order keeps a hand-inspected schedule legible. Strips that fail to
   resolve are dropped with a warning rather than failing the whole sync."
  [grid channel-tag source-id-by-name & {:keys [schedule-name]}]
  (let [strips (sort-by (comp parse-mins :start) (:strips grid))
        built  (mapv #(->slot % channel-tag source-id-by-name) strips)
        ok     (remove :error built)
        errs   (filter :error built)
        slots  (into [] (map-indexed (fn [i s] (assoc s :slot-index i))) ok)]
    {:name (or schedule-name (str (:channel grid) " (auto)"))
     :slots slots
     :warnings (mapv :error errs)}))
