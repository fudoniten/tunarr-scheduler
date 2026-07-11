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
       - random:<category> (pooled): first confirm the category is a real tag
         in the profile at all (tag_aggregates / genres / tag_runtime_histograms,
         bare or 'genre:'-prefixed) — a category that exists in none of them is
         hallucinated, has no media behind it, and is a shortfall. When it does
         exist: repeats are fine, so check the pool is non-trivial —
         episode_count for that category ≥ a small floor. ALSO
         checked for duration fit: does the category's per-tag runtime
         histogram (CatalogProfile.tag_runtime_histograms) have content within
         a tolerance of the strip's own wall-clock length? This is the
         grid-authoring-time half of the duration-aware scheduling work (see
         DURATION_AWARE_SCHEDULING.md §3.4) — Pseudovision's air-time
         selection (pseudovision#119) already fits content to slots, but
         without this check a strip could still be authored at a length its
         category has no content anywhere near, which Pseudovision's
         selection can only paper over (falling back to closest available),
         not fix. The worse of the two random-kind statuses wins; messages
         concatenate.
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
            [tunarr.scheduler.scheduling.calendar :as cal])
  (:import [java.time LocalDate]))

(def ^:const margin
  "Comfort margin above bare sufficiency for sequential series: below this the
   finding is 'tight' rather than 'ok'."
  1.2)

(def ^:const random-pool-floor
  "Minimum pooled episode_count for a random:<category> strip to be comfortable."
  10)

(def ^:const duration-fit-tolerance-minutes
  "Slack around a strip's own wall-clock length when checking whether its
   random:<category> pool has content at roughly that duration. Mirrors
   Pseudovision's air-time tolerance (daily-slots.clj's
   default-fit-tolerance-minutes) so a 'tight' finding here and a
   'closest available' fallback at air time describe the same boundary."
  15)

(def ^:const duration-fit-floor
  "Minimum in-tolerance item_count for a random:<category> strip's duration to
   be comfortable, not just technically non-empty (mirrors random-pool-floor's
   role for the episode-count check, at a smaller scale since this counts
   items within one narrow duration window rather than the whole category)."
  3)

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

(defn- parse-mins [hhmm]
  (let [[h m] (map parse-long (str/split hhmm #":"))]
    (+ (* 60 h) m)))

(defn- show-available [catalog media-id]
  (some #(when (= media-id (:media_id %)) (:available_episode_count %))
        (:shows catalog)))

(defn- tag-matches?
  "Whether a stored tag value (a tag_aggregate's :tag, a genre's :genre, a
   histogram's :tag) names `category`, matched case-insensitively in either its
   bare form ('sitcom') or the dimension-qualified 'genre:'-prefixed form
   ('genre:sitcom').

   `random:<category>` strips carry a bare category, but the dimension model
   stores tags prefixed ('genre:sitcom', 'channel:hua') — so a bare-only equality
   check silently misses every tag_aggregate. This mirrors the same duality
   pseudovision's `resolve-by-category` accepts at air time (and that
   `tag-runtime-histogram` below already applied for the duration-fit check)."
  [tag-value category]
  (when (and tag-value category)
    (let [t      (str/lower-case (str tag-value))
          needle (str/lower-case category)]
      (or (= t needle) (= t (str "genre:" needle))))))

(defn- category-episode-count
  "Pooled episode_count for a bare `random:<category>` argument, read from the
   catalog profile's :tag_aggregates (primary, dimension model) or :genres
   (deprecated fallback). nil when the category names no tag in either — see
   `category-known?` for what that means."
  [catalog category]
  (when category
    (or
      ;; Tag-based lookup (primary)
      (some #(when (tag-matches? (:tag %) category) (:episode_count %))
            (:tag_aggregates catalog))
      ;; Genre-based fallback (backward compat)
      (some #(when (tag-matches? (:genre %) category) (:episode_count %))
            (:genres catalog)))))

(defn- headroom [available required]
  (when (pos? required) (double (/ available required))))

;; ---------------------------------------------------------------------------
;; Duration fit (random:<category> strips only)
;;
;; Episode-count/pool-floor checks above answer "is there enough content in
;; this category"; this answers a different question the old checker never
;; asked at all — "is any of it roughly the right LENGTH for this strip".
;; Without it, a random:movie strip could be authored at 60 minutes even when
;; every movie in the catalog runs 90+ — Pseudovision's air-time selection
;; (daily-slots.clj) can only fall back to "closest available" for a mismatch
;; like that, not fix the strip itself.
;; ---------------------------------------------------------------------------

(defn- strip-duration-minutes
  "A strip's own wall-clock length in minutes. `end <= start` wraps past
   midnight, same convention as `minute-spans`."
  [strip]
  (let [s (parse-mins (:start strip)) e (parse-mins (:end strip))]
    (if (> e s) (- e s) (+ (- 1440 s) e))))

(defn- tag-runtime-histogram
  "The {:tag :buckets [...]} entry for `category` in
   catalog_profile.tag_runtime_histograms, or nil if the category has no
   dimensioned histogram data (an older CatalogProfile, or a category with no
   matching tag at all).

   `random:<category>` carries a bare category name (e.g. 'movie'), but
   Pseudovision's tag storage — and therefore every :tag value this field
   contains — is prefix-qualified ('genre:movie'). Matching only the bare
   form would silently never find anything for the common case. `tag-matches?`
   applies the same bare-or-`genre:`-prefixed duality
   `pseudovision.http.api.daily-slots/resolve-by-category` matches at air time."
  [catalog category]
  (when category
    (some #(when (tag-matches? (:tag %) category) %)
          (:tag_runtime_histograms catalog))))

(defn- category-known?
  "Whether `category` (the bare argument of a `random:<category>` strip) names a
   real tag anywhere in the catalog profile — its :tag_aggregates, :genres, or
   :tag_runtime_histograms — matched bare or 'genre:'-prefixed (`tag-matches?`).

   The CatalogProfile is the entire media report the channel is scheduled
   against, so a category absent from all three of its tag views doesn't exist
   in the media available to this channel. A `random:<category>` strip naming
   such a category is scheduling a tag that was hallucinated: at playout it
   resolves to an empty Pseudovision collection (dead air). `finding` flags it
   as a shortfall rather than silently letting it through. (A count of 0 is a
   *known* tag with an empty pool — a different, already-handled case.)"
  [catalog category]
  (boolean (or (category-episode-count catalog category)
               (tag-runtime-histogram catalog category))))

(defn- bucket-overlaps-window? [bucket lo hi]
  (let [bmin (:min_minutes bucket)
        bmax (or (:max_minutes bucket) Long/MAX_VALUE)]
    (and (<= bmin hi) (<= lo bmax))))

(defn- fitting-item-count
  "Sum of item_count across every bucket in `histogram` whose runtime range
   overlaps [lo, hi] (an open-ended top bucket's nil max_minutes is treated
   as unbounded)."
  [histogram lo hi]
  (reduce + 0 (keep (fn [b] (when (bucket-overlaps-window? b lo hi) (:item_count b)))
                     (:buckets histogram))))

(def ^:private status-rank {"ok" 0, "tight" 1, "shortfall" 2})

(defn- worse-status [a b]
  (if (>= (status-rank a 0) (status-rank b 0)) a b))

(defn- combine-findings
  "Merges two finding maps (each carrying at least :status/:message) into one:
   :status is the worse of the two, :message concatenates both non-blank
   messages. Other keys (:episodes_available, :headroom_ratio, ...) come from
   `a` unchanged."
  [a b]
  (let [status (worse-status (:status a) (:status b))
        msgs   (remove str/blank? [(:message a) (:message b)])]
    (assoc a :status status :message (str/join "; " msgs))))

(defn- duration-fit-finding
  "Whether `catalog`'s per-tag runtime histogram for `category` has content
   within `duration-fit-tolerance-minutes` of `strip`'s own wall-clock length.

   Returns nil (nothing to merge) when the catalog profile carries no
   histogram data for this category at all — an older Pseudovision build, or
   simply no matching tag — rather than manufacturing a false shortfall out of
   an absent signal. Otherwise a {:status :message} finding to combine with
   the pool-floor finding via `combine-findings`."
  [strip catalog category]
  (when-let [histogram (tag-runtime-histogram catalog category)]
    (let [target    (strip-duration-minutes strip)
          lo        (max 0 (- target duration-fit-tolerance-minutes))
          hi        (+ target duration-fit-tolerance-minutes)
          fit-count (fitting-item-count histogram lo hi)]
      (cond
        (zero? fit-count)
        {:status "shortfall"
         :message (format "no '%s' content within %dmin of the strip's %dmin length"
                          category duration-fit-tolerance-minutes target)}

        (< fit-count duration-fit-floor)
        {:status "tight"
         :message (format "only %d '%s' item(s) within %dmin of the strip's %dmin length"
                          fit-count category duration-fit-tolerance-minutes target)}

        :else
        {:status "ok" :message ""}))))

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

       ;; Pooled rotation: repeats fine, just confirm the pool is non-trivial
       ;; AND that some of it is roughly the right length for the strip.
       (= kind "random")
       (let [category (media-arg media-id)
             pool     (category-episode-count catalog category)
             pool-finding
             (cond
               ;; The category names no tag anywhere in the catalog profile ⇒ a
               ;; hallucinated tag with no media behind it (it resolves to an
               ;; empty collection at playout). Block on it so the repair loop
               ;; swaps it for a category that actually exists in the report.
               (not (category-known? catalog category))
               {:episodes_available 0
                :headroom_ratio (headroom 0 required)
                :status "shortfall"
                :message (format (str "category '%s' does not exist in the catalog profile — "
                                      "no such tag in the media available to this channel "
                                      "(hallucinated?)")
                                 category)}

               ;; A real tag, but the profile reports no pooled episode_count for
               ;; it (an older or partial profile) ⇒ can't assess capacity; don't
               ;; invent one, and don't mistake it for a hallucination either.
               (nil? pool)
               {:episodes_available 0
                :headroom_ratio (headroom 0 required)
                :status "ok"
                :message (format "category '%s' present but pool size not reported; capacity not assessed"
                                 category)}

               :else
               {:episodes_available pool
                :headroom_ratio (headroom pool required)
                :status (cond (zero? pool)               "shortfall"
                              (< pool random-pool-floor) "tight"
                              :else                       "ok")
                :message (cond (zero? pool) (format "no content in category '%s'" category)
                               (< pool random-pool-floor)
                               (format "small pool: %d items in category '%s'" pool category)
                               :else "")})
             duration-finding (duration-fit-finding strip catalog category)]
         (if duration-finding
           (combine-findings pool-finding duration-finding)
           pool-finding))

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
;; Public API
;; ---------------------------------------------------------------------------

(defn check
  "Produce a FeasibilityReport (contracts/FeasibilityReport) for `grid` against
   `catalog-profile` over [horizon-start, horizon-end) (end exclusive). Dates may
   be LocalDate or ISO strings."
  [grid catalog-profile horizon-start horizon-end]
  (let [dates    (horizon-dates horizon-start horizon-end)
        findings (mapv #(finding % catalog-profile dates) (:strips grid))
        olaps    (find-overlaps (:strips grid))
        uncov    (uncovered-intervals grid)
        any?     (fn [status] (some #(= status (:status %)) findings))
        overall  (cond
                   (any? "shortfall")                       "blocked"
                   (or (any? "tight") (seq olaps) (seq uncov)) "warnings"
                   :else                                    "ok")]
    {:horizon_start (str (->date horizon-start))
     :horizon_end   (str (->date horizon-end))
     :overall_status overall
     :strip_findings findings
     :overlaps olaps
     :uncovered_intervals uncov
     :notes []}))
