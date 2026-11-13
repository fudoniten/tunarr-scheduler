(ns tunarr.scheduler.scheduling.feasibility
  "Deterministic feasibility checking for a frozen Grid against a CatalogProfile.

   This is the arithmetic the LLM never does. Given a grid, a catalog profile,
   and a planning horizon, it produces a FeasibilityReport (contracts/§2.4) that
   the propose→check→repair loop feeds back to Tunabrain's repair endpoint.

   Pure function, no I/O. Algorithm from handoff spec §4.3:

   • Per-strip capacity — slots_required = airings of the strip over the
     horizon (dates whose weekday the strip's day-pattern matches).
     Pseudovision fills a strip with as many items as fit back to back, so one
     airing uses floor(strip length ÷ mean item runtime) items, at least one
     (`items-per-airing`). The retired DailySlots push aired one item per
     slot; the checks used to assume that. Then by media_id kind:
       - series:<id> sequential: episodes needed = Σ over every strip of that
         show of airings × episodes per airing (the show's
         avg_runtime_minutes). shortfall if available < needed; tight if
         available < needed × MARGIN; else ok.
       - random:<category> (pooled): first confirm the category is a real tag
         in the profile at all (tag_aggregates / genres / tag_runtime_histograms,
         bare or 'genre:'-prefixed) — a category that exists in none of them is
         hallucinated and is a shortfall. When it does exist, check the pool is
         non-trivial (episode_count ≥ a small floor) and judge repetition: the
         items per week every same-category strip takes (airings × items per
         airing) divided by how many items in the category's runtime histogram
         are no longer than this strip. The worse status wins; messages
         concatenate.
       - movie:<id> / program:<id>: a single item; shortfall if it runs
         longer than the strip, tight if it would air more than once.
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

(def ^:const duration-fit-tight-reps-per-week
  "Weekly repetition rate — airings per week that draw on a random:<category>
   strip's duration pool, divided by the number of distinct in-tolerance items —
   above which the strip is 'tight'. At 1.5, a slot may air up to ~1.5× the size
   of its right-length pool each week before it's flagged; a single occasional
   short slot backed by a handful of items stays fine, but stacking several a day
   onto that same handful does not."
  1.5)

(def ^:const duration-fit-shortfall-reps-per-week
  "Weekly repetition rate (see `duration-fit-tight-reps-per-week`) at or above
   which the strip is a 'shortfall', not merely tight — the pool is so thin for
   how often it airs that the same few items would grind round multiple times a
   week (the truncated-Simpsons-15×/week failure). Blocks the grid so the repair
   loop thins the frequency or moves the content to a special/occasional slot."
  3.0)

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

(defn- tag-key
  "Case- and separator-insensitive comparison key for a tag value: lowercased,
   `&` read as `and`, every run of characters other than letters, digits and
   `:` collapsed to one `-`. TS curation stores snake_case tags (`dark_comedy`)
   while Jellyfin genres and normalised overrides are kebab-case
   (`dark-comedy`); both reduce to the same key here, matching
   pseudovision.db.media/resolve-playable-by-tag at air time."
  [s]
  (-> (str s)
      str/lower-case
      (str/replace "&" "and")
      (str/replace #"[^a-z0-9:]+" "-")
      (str/replace #"(^-+)|(-+$)" "")))

(defn- tag-matches?
  "Whether a stored tag value (a tag_aggregate's :tag, a genre's :genre, a
   histogram's :tag) names `category`, matched case-insensitively in either its
   bare form ('sitcom') or the dimension-qualified 'genre:'-prefixed form
   ('genre:sitcom'), ignoring hyphen/underscore style (`tag-key`).

   `random:<category>` strips carry a bare category, but the dimension model
   stores tags prefixed ('genre:sitcom', 'channel:hua') — so a bare-only equality
   check silently misses every tag_aggregate. This mirrors the same duality
   pseudovision's `resolve-by-category` accepts at air time (and that
   `tag-runtime-histogram` below already applied for the duration-fit check)."
  [tag-value category]
  (when (and tag-value category)
    (let [t      (tag-key tag-value)
          needle (tag-key category)]
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

(defn known-random-category?
  "Public wrapper over `category-known?`: whether the bare `category` of a
   `random:<category>` strip names a real tag in `catalog` (a CatalogProfile).
   Exposed so the freeze step can deterministically neutralise a dead
   `random:<category>` (one feasibility would flag as hallucinated) even after
   the LLM's repair budget is exhausted with it still present — the same
   predicate, so the guard and the checker never disagree."
  [catalog category]
  (category-known? catalog category))

(def ^:private default-open-bucket-width-minutes
  "Assumed width of an open-ended (no :max_minutes) top histogram bucket."
  15)

(defn- bucket-fit
  "How many of `bucket`'s items run no longer than `minutes`: all of them when
   the bucket ends at or below it, none when it starts above it, and the share
   of the bucket's range below it otherwise (an open-ended top bucket is taken
   as `default-open-bucket-width-minutes` wide)."
  ^double [bucket minutes]
  (let [lo    (:min_minutes bucket)
        hi    (or (:max_minutes bucket) (+ lo default-open-bucket-width-minutes))
        n     (double (:item_count bucket 0))]
    (cond
      (<= hi minutes) n
      (> lo minutes)  0.0
      :else           (* n (/ (double (- minutes lo)) (max 1 (- hi lo)))))))

(defn- fitting-items
  "Items in `histogram` short enough to air in a strip of `minutes`, and their
   mean runtime: `{:count n :mean-minutes m}` (m nil when n is 0). Pseudovision
   packs a strip with as many items as fit, so an item fits a strip when it is
   no longer than the strip, not when it is close to the strip's length."
  [histogram minutes]
  (let [parts (for [b (:buckets histogram)
                    :let [n (bucket-fit b minutes)]
                    :when (pos? n)
                    :let [hi  (min minutes (or (:max_minutes b)
                                               (+ (:min_minutes b) default-open-bucket-width-minutes)))
                          mid (/ (+ (:min_minutes b) hi) 2.0)]]
                [n mid])
        n     (reduce + 0.0 (map first parts))]
    {:count        n
     :mean-minutes (when (pos? n) (/ (reduce + 0.0 (map (fn [[k m]] (* k m)) parts)) n))}))

(defn items-per-airing
  "How many items one airing of a `minutes`-long strip uses when its items run
   `mean-minutes` on average: as many as fit back to back, at least one.
   Pseudovision fills a block with items until the next one doesn't fit; the
   retired DailySlots push aired one item per slot, which is what the checks
   here used to assume."
  [minutes mean-minutes]
  (if (and mean-minutes (pos? mean-minutes))
    (max 1 (long (Math/floor (/ (double minutes) mean-minutes))))
    1))

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

(defn- weekly-airings
  "How many times a strip airs in a week — one airing per weekday its day-pattern
   covers (weekdays ⇒ 5, daily ⇒ 7, [\"sat\"] ⇒ 1). Horizon-independent, so a
   repetition rate built on it (`duration-fit-finding`) means the same thing
   whether the grid is checked over one week or a full quarter."
  [strip]
  (count (cal/pattern->codes (:days strip))))

(defn- same-category-strips
  "Every `random:<category>` strip in `all-strips` whose bare category is
   `category` (case-insensitive)."
  [all-strips category]
  (filter (fn [s]
            (let [mid (-> s :content :media_id)
                  c   (media-arg mid)]
              (and (= "random" (media-kind mid)) c
                   (= (str/lower-case c) (str/lower-case category)))))
          all-strips))

(defn- category-weekly-demand
  "Items per week that the `random:<category>` strips in `all-strips` take from
   the pool of items that fit a `minutes`-long strip: for each same-category
   strip, weekly airings × items per airing (from the mean runtime of the items
   that fit it).

   Repetition has to be judged across strips, not one at a time: three separate
   half-hour comedy strips a day each look individually survivable, but together
   they pull ~15 airings/week out of the same five or six half-hour comedies.
   A strip no longer than `minutes` draws only on this pool, so all of its
   demand counts. A longer strip draws on a bigger pool that contains this one,
   so only the share of its picks that land here counts (this pool's size over
   its pool's size)."
  [all-strips category histogram minutes]
  (let [here (:count (fitting-items histogram minutes))]
    (reduce
     + 0.0
     (for [s (same-category-strips all-strips category)
           :let [mins (strip-duration-minutes s)
                 {n :count mean :mean-minutes} (fitting-items histogram mins)
                 per-week (* (weekly-airings s) (items-per-airing mins mean))]
           :when (pos? n)]
       (if (<= mins minutes)
         per-week
         (* per-week (min 1.0 (/ here n))))))))

(defn- duration-fit-finding
  "Whether `catalog`'s per-tag runtime histogram for `category` has enough
   content short enough for `strip` for how often the grid draws on it. An item
   fits when it is no longer than the strip, because Pseudovision packs a block
   with every item that fits back to back. The check is a *repetition rate*:
   the items per week every same-category strip takes (`category-weekly-demand`)
   divided by the number of items that fit. A pool of five half-hour comedies is
   fine behind one occasional slot and a disaster behind three-a-day.

   Returns nil (nothing to merge) when the catalog profile carries no histogram
   data for this category at all, rather than manufacturing a false shortfall
   out of an absent signal. Otherwise a {:status :message} finding to combine
   with the pool-floor finding via `combine-findings`."
  [strip catalog category all-strips]
  (when-let [histogram (tag-runtime-histogram catalog category)]
    (let [target    (strip-duration-minutes strip)
          {fit :count mean :mean-minutes} (fitting-items histogram target)
          fit-count (long (Math/round (double fit)))
          per-air   (items-per-airing target mean)
          demand    (long (Math/round (double (category-weekly-demand all-strips category histogram target))))
          reps      (when (pos? fit) (/ demand fit))]
      (cond
        (< fit 0.5)
        {:status "shortfall"
         :message (format "no '%s' content short enough for the strip's %dmin length"
                          category target)}

        (>= reps duration-fit-shortfall-reps-per-week)
        {:status "shortfall"
         :message (format (str "only %d '%s' item(s) fit the strip's %dmin length "
                               "(~%d per airing) for %d item airing(s)/week across the grid "
                               "— each would repeat ~%.1f×/week")
                          fit-count category target per-air demand reps)}

        (>= reps duration-fit-tight-reps-per-week)
        {:status "tight"
         :message (format (str "only %d '%s' item(s) fit the strip's %dmin length "
                               "(~%d per airing) for %d item airing(s)/week — repeats ~%.1f×/week")
                          fit-count category target per-air demand reps)}

        :else
        {:status "ok" :message ""}))))

(defn- show-entry [catalog media-id]
  (some #(when (= media-id (:media_id %)) %) (:shows catalog)))

(defn- series-episodes-required
  "Episodes the grid's strips of series `media-id` use over the horizon: for each
   strip of that series, its airings × the episodes one airing packs (strip
   length ÷ the show's average runtime). Summed across strips because they share
   one rotation: two daily strips of the same show use it up twice as fast."
  [all-strips media-id catalog dates]
  (let [avg (:avg_runtime_minutes (show-entry catalog media-id))]
    (reduce + 0
            (for [s all-strips
                  :when (= media-id (-> s :content :media_id))]
              (* (slots-required s dates)
                 (items-per-airing (strip-duration-minutes s) avg))))))

;; ---------------------------------------------------------------------------
;; Per-strip capacity finding
;; ---------------------------------------------------------------------------

(defn- finding
  "A StripFeasibility map for one strip over the horizon. `all-strips` is the
   whole grid's strip list, so the duration-fit check can weigh this strip's
   repetition against every other same-length same-category strip drawing on the
   same pool."
  [strip catalog dates all-strips]
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
       ;; Each airing packs as many episodes as fit the strip, and every strip
       ;; of the same show shares one rotation, so count episodes, not airings.
       (and (= kind "series") (= strategy "sequential"))
       (let [available (or (show-available catalog media-id) 0)
             needed    (series-episodes-required all-strips media-id catalog dates)
             status (cond
                      (< available needed)            "shortfall"
                      (< available (* needed margin)) "tight"
                      :else                           "ok")]
         {:episodes_available available
          :headroom_ratio (headroom available needed)
          :status status
          :message (case status
                     "shortfall" (format "needs %d distinct episodes for %d airing(s) of this series across the grid, only %d available"
                                          needed required available)
                     "tight"     (format "thin margin: %d available for %d episodes"
                                          available needed)
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
             duration-finding (duration-fit-finding strip catalog category all-strips)]
         (if duration-finding
           (combine-findings pool-finding duration-finding)
           pool-finding))

       ;; A single movie or Grout program: it must fit the strip, and is
       ;; suspicious if it repeats.
       (contains? #{"movie" "program"} kind)
       (let [runtime (:avg_runtime_minutes (show-entry catalog media-id))
             mins    (strip-duration-minutes strip)]
         (cond
           (and runtime (> runtime mins))
           {:episodes_available 1
            :headroom_ratio (headroom 1 required)
            :status "shortfall"
            :message (format "%s runs %dmin, longer than the strip's %dmin"
                             kind (long (Math/ceil runtime)) mins)}

           (> required 1)
           {:episodes_available 1
            :headroom_ratio (headroom 1 required)
            :status "tight"
            :message (format "single %s would air %d times over the horizon" kind required)}

           :else
           {:episodes_available 1
            :headroom_ratio (headroom 1 required)
            :status "ok"
            :message ""}))

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
        strips   (:strips grid)
        findings (mapv #(finding % catalog-profile dates strips) strips)
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
