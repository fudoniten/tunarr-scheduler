(ns tunarr.scheduler.scheduling.feasibility
  "Deterministic feasibility checking for a frozen Grid against a CatalogProfile.

   This is the arithmetic the LLM never does. Given a grid, a catalog profile,
   and a planning horizon, it produces a FeasibilityReport (contracts/§2.4) that
   the propose→check→repair loop feeds back to Tunabrain's repair endpoint.

   Pure function, no I/O. Algorithm from handoff spec §4.3:

   • Per-strip capacity — slots_required = airings of the strip over the
     horizon (dates whose weekday the strip's day-pattern matches). Then by
     media_id kind:
       - series:<id> sequential: episodes_available = that show's
         available_episode_count; want available ≥ slots_required.
         shortfall if available < slots_required; tight if
         available < slots_required × MARGIN; else ok.
       - random:<category> (pooled): repeats are fine, so check the pool is
         non-trivial — episode_count for that category ≥ a small floor.
       - movie:<id>: a single item; tight if it would air more than once.
   • Overlap — base-grid strips whose day-patterns intersect AND whose time
     windows overlap (ambiguous authoring worth surfacing).
   • Coverage — broadcast-day time covered by no strip when there is no
     default_content.
   • overall_status — blocked if any shortfall; else warnings if any
     tight/overlap/uncovered; else ok.

   NOTE: tunabrain defines only the FeasibilityReport *contract*; the checker
   lives here (the LLM is stateless and never does this math). The thresholds
   and the random-pool floor are local policy knobs; reconcile if upstream ever
   ships a reference implementation, as we did for the expander."
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [tunarr.scheduler.scheduling.calendar :as cal]
            [tunarr.scheduler.scheduling.policy :as policy])
  (:import [java.time LocalDate]))

(def ^:const margin
  "Comfort margin above bare sufficiency for sequential series: below this the
   finding is 'tight' rather than 'ok'."
  1.2)

(def ^:const random-pool-floor
  "Minimum pooled episode_count for a random:<category> strip to be comfortable."
  10)

;; ---------------------------------------------------------------------------
;; Horizon + media helpers
;; ---------------------------------------------------------------------------

(defn- ->date ^LocalDate [d]
  (if (instance? LocalDate d) d (LocalDate/parse (str d))))

(defn- horizon-dates
  "Dates in [start, end) (end exclusive)."
  [start end]
  (let [s (->date start) e (->date end)]
    (->> (iterate #(.plusDays ^LocalDate % 1) s)
         (take-while #(.isBefore ^LocalDate % e)))))

(defn- slots-required [strip dates]
  (count (filter #(cal/date-matches? (:days strip) %) dates)))

(defn- media-kind [media-id]
  (first (str/split (str media-id) #":" 2)))

(defn- media-arg [media-id]
  (second (str/split (str media-id) #":" 2)))

(defn- show-available [catalog media-id]
  (some #(when (= media-id (:media_id %)) (:available_episode_count %))
        (:shows catalog)))

(defn- category-episode-count [catalog category]
  (when category
    (or
      ;; Tag-based lookup (primary)
      (some #(when (= (str/lower-case (:tag %)) (str/lower-case category))
               (:episode_count %))
            (:tag_aggregates catalog))
      ;; Genre-based fallback (backward compat)
      (some #(when (= (str/lower-case (:genre %)) (str/lower-case category))
               (:episode_count %))
            (:genres catalog)))))

(defn- headroom [available required]
  (when (pos? required) (double (/ available required))))

;; ---------------------------------------------------------------------------
;; Per-strip capacity finding
;; ---------------------------------------------------------------------------

(defn- finding
  "A StripFeasibility map for one strip over the horizon."
  [strip catalog dates]
  (let [media-id (-> strip :content :media_id)
        strategy (-> strip :content (:strategy "sequential"))
        required (slots-required strip dates)
        base     {:rule_id (:strip_id strip) :media_id media-id
                  :slots_required required}
        kind     (media-kind media-id)]
    (merge
     base
     (cond
       ;; Sequential series: avoid repeats ⇒ need enough distinct episodes.
       (and (= kind "series") (= strategy "sequential"))
       (let [available (or (show-available catalog media-id) 0)
             status (cond
                      (< available required)            "shortfall"
                      (< available (* required margin)) "tight"
                      :else                             "ok")]
         {:episodes_available available
          :headroom_ratio (headroom available required)
          :status status
          :message (case status
                     "shortfall" (format "needs %d distinct episodes, only %d available"
                                          required available)
                     "tight"     (format "thin margin: %d available for %d airings"
                                          available required)
                     "")})

       ;; Non-sequential series: repeats acceptable, only an empty library bites.
       (= kind "series")
       (let [available (or (show-available catalog media-id) 0)]
         {:episodes_available available
          :headroom_ratio (headroom available required)
          :status (if (zero? available) "shortfall" "ok")
          :message (if (zero? available) "no available episodes for this series" "")})

       ;; Pooled rotation: repeats fine, just confirm the pool is non-trivial.
       (= kind "random")
       (let [category (media-arg media-id)
             pool     (category-episode-count catalog category)]
         (if (nil? pool)
           {:episodes_available 0
            :headroom_ratio (headroom 0 required)
            :status "ok"
            :message (format "category '%s' not found in catalog profile; pool not assessed"
                             category)}
           {:episodes_available pool
            :headroom_ratio (headroom pool required)
            :status (cond (zero? pool)               "shortfall"
                          (< pool random-pool-floor) "tight"
                          :else                       "ok")
            :message (cond (zero? pool) (format "no content in category '%s'" category)
                           (< pool random-pool-floor)
                           (format "small pool: %d items in category '%s'" pool category)
                           :else "")}))

       ;; Single movie: fine once, suspicious if it repeats.
       (= kind "movie")
       {:episodes_available 1
        :headroom_ratio (headroom 1 required)
        :status (if (> required 1) "tight" "ok")
        :message (if (> required 1)
                   (format "single movie would air %d times over the horizon" required)
                   "")}

       :else
       {:episodes_available 0
        :headroom_ratio (headroom 0 required)
        :status "ok"
        :message (format "unrecognized media_id kind '%s'" kind)}))))

;; ---------------------------------------------------------------------------
;; Overlap detection (within the base grid)
;; ---------------------------------------------------------------------------

(defn- parse-mins [hhmm]
  (let [[h m] (map parse-long (str/split hhmm #":"))]
    (+ (* 60 h) m)))

(defn- ->hhmm [mins]
  (format "%02d:%02d" (quot mins 60) (mod mins 60)))

(defn- minute-spans
  "On-air sub-intervals of a strip within a single 24h cycle, [lo hi) in minutes.
   `end <= start` wraps past midnight."
  [start end]
  (let [s (parse-mins start) e (parse-mins end)]
    (if (> e s) [[s e]] [[s 1440] [0 e]])))

(defn- spans-overlap
  "Overlapping [lo hi) sub-intervals between two span lists."
  [a-spans b-spans]
  (for [[al ah] a-spans
        [bl bh] b-spans
        :let [lo (max al bl) hi (min ah bh)]
        :when (< lo hi)]
    [lo hi]))

(defn- overlap-message [a b common-days [lo hi]]
  (format "%s overlaps %s on %s %s-%s"
          (:strip_id a) (:strip_id b)
          (cal/render-days common-days) (->hhmm lo) (->hhmm hi)))

(defn- find-overlaps
  "Human-readable conflicts between base-grid strips whose day-patterns intersect
   and whose time windows overlap."
  [strips]
  (let [indexed (map-indexed vector strips)]
    (->> (for [[i a] indexed
               [j b] indexed
               :when (< i j)
               :let [common (set/intersection (cal/pattern->codes (:days a))
                                              (cal/pattern->codes (:days b)))]
               :when (seq common)
               :let [overlap (first (spans-overlap (minute-spans (:start a) (:end a))
                                                   (minute-spans (:start b) (:end b))))]
               :when overlap]
           (overlap-message a b common overlap))
         vec)))

;; ---------------------------------------------------------------------------
;; Coverage gaps (only meaningful when there is no default_content)
;; ---------------------------------------------------------------------------

(defn- gaps-in-day
  "Complement of a set of [lo hi) spans within [0 1440), as [lo hi) gaps."
  [spans]
  (let [sorted (sort-by first spans)]
    (loop [cursor 0, remaining sorted, gaps []]
      (if-let [[lo hi] (first remaining)]
        (recur (max cursor hi)
               (rest remaining)
               (if (< cursor lo) (conj gaps [cursor lo]) gaps))
        (if (< cursor 1440) (conj gaps [cursor 1440]) gaps)))))

(defn- coverage-for-weekday
  "All on-air [lo hi) spans experienced on weekday `code`, including the early
   hours spilled over from the previous day's cross-midnight strips."
  [strips code]
  (let [prev (nth cal/weekday-codes (mod (dec (.indexOf cal/weekday-codes code)) 7))]
    (concat
     ;; Strips airing today: their same-day portion.
     (for [s strips
           :when (cal/matches? (:days s) code)
           span (let [m (minute-spans (:start s) (:end s))]
                  (if (= 1 (count m)) m [(first m)]))]   ; [s end] or [s 1440]
       span)
     ;; Strips airing yesterday that wrap into this morning: their [0 e) portion.
     (for [s strips
           :when (cal/matches? (:days s) prev)
           :let [m (minute-spans (:start s) (:end s))]
           :when (= 2 (count m))]
       (second m)))))

(defn- uncovered-intervals
  "Per-weekday coverage gaps, grouped by identical gap set into readable lines.
   Empty when default_content is present (it fills everything)."
  [grid]
  (if (:default_content grid)
    []
    (let [strips (:strips grid)
          by-day (into {} (for [code cal/weekday-codes]
                            [code (set (gaps-in-day (coverage-for-weekday strips code)))]))
          ;; group weekdays that share the same gap set
          grouped (reduce (fn [m [code gaps]]
                            (update m gaps (fnil conj #{}) code))
                          {} by-day)]
      (->> grouped
           (mapcat (fn [[gaps codes]]
                     (when (seq gaps)
                       (let [label (cal/render-days codes)]
                         (for [[lo hi] (sort-by first gaps)]
                           (format "%s %s-%s" label (->hhmm lo) (->hhmm hi)))))))
           (remove nil?)
           sort
           vec))))

;; ---------------------------------------------------------------------------
;; Content-policy (watershed) violations
;; ---------------------------------------------------------------------------

(defn- profile-tag-resolver
  "A media_id → catalog-tag-seq lookup built from a CatalogProfile's `:shows`
   (each ShowProfile carries `:tags`). Movies not present in `:shows` resolve to
   nil, so only their strip's own `category_filters` are consulted."
  [catalog-profile]
  (let [by-id (into {} (map (juxt :media_id :tags)) (:shows catalog-profile))]
    (fn [media-id] (get by-id media-id))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn check
  "Produce a FeasibilityReport (contracts/FeasibilityReport) for `grid` against
   `catalog-profile` over [horizon-start, horizon-end) (end exclusive). Dates may
   be LocalDate or ISO strings.

   The optional `policy` (contracts/ContentPolicy) adds deterministic
   content-placement checks: any strip that airs restricted content (e.g.
   `audience:adult`) in a forbidden part of the day yields a
   `:watershed_violations` entry and forces `overall_status` to \"blocked\", so
   the propose→repair loop re-places it."
  ([grid catalog-profile horizon-start horizon-end]
   (check grid catalog-profile horizon-start horizon-end nil))
  ([grid catalog-profile horizon-start horizon-end policy]
   (let [dates      (horizon-dates horizon-start horizon-end)
         findings   (mapv #(finding % catalog-profile dates) (:strips grid))
         olaps      (find-overlaps (:strips grid))
         uncov      (uncovered-intervals grid)
         violations (if (seq (:watersheds policy))
                      (policy/grid-violations grid policy (profile-tag-resolver catalog-profile))
                      [])
         any?       (fn [status] (some #(= status (:status %)) findings))
         overall    (cond
                      (or (any? "shortfall") (seq violations)) "blocked"
                      (or (any? "tight") (seq olaps) (seq uncov)) "warnings"
                      :else                                    "ok")]
     {:horizon_start (str (->date horizon-start))
      :horizon_end   (str (->date horizon-end))
      :overall_status overall
      :strip_findings findings
      :overlaps olaps
      :uncovered_intervals uncov
      :watershed_violations violations
      ;; Surface violations in :notes too, so a Tunabrain repair endpoint that
      ;; does not read the local :watershed_violations field still gets the why.
      :notes (vec violations)})))
