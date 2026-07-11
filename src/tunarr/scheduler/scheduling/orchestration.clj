(ns tunarr.scheduler.scheduling.orchestration
  "The propose → check → repair → freeze loop, per channel.

   This ties the pieces together (handoff §4.6). Quarterly: assemble a
   CatalogProfile from Pseudovision, ask Tunabrain to propose a Grid, run the
   deterministic feasibility checker, and — while there are blocking shortfalls
   and repair budget remains — ask Tunabrain to repair, re-checking each round.
   Then freeze + store the grid with its feasibility snapshot. Monthly: ask
   Tunabrain for sparse overrides against the frozen grid and store them.

   Operator guidance (the per-channel manual-input surface) is pulled in and fed
   to the propose calls; it steers but never gates. The weekly deterministic
   publish step lives in `scheduling.integration/publish-week!` (no Tunabrain
   call).

   `components` is a map of {:executor :tunabrain :pv-config}. The three external
   calls — the CatalogProfile fetch and the two Tunabrain proposals — are taken
   from `components` (`:fetch-profile`, `:propose-grid`, `:repair-grid`,
   `:propose-overrides`) and default to the real implementations, so callers can
   inject stubs without global redefs. `channel-spec` is the {:name :description}
   stub Tunabrain expects; its :name is also the storage key."
  (:require [clojure.string :as str]
            [taoensso.timbre :as log]
            [tunarr.scheduler.tunabrain :as tb]
            [tunarr.scheduler.backends.pseudovision.client :as pv]
            [tunarr.scheduler.scheduling.candidates :as candidates]
            [tunarr.scheduler.scheduling.integration :as integ]
            [tunarr.scheduler.scheduling.feasibility :as feasibility]
            [tunarr.scheduler.scheduling.native-schedule :as native]
            [tunarr.scheduler.scheduling.review :as review]
            [tunarr.scheduler.scheduling.storage :as storage]
            [tunarr.scheduler.scheduling.plans :as plans]))

(defn- guidance [executor channel-uuid]
  (or (storage/get-guidance executor channel-uuid) {}))

(defn- slugify [s]
  (-> s str/lower-case (str/replace #"[^a-z0-9]+" "-") (str/replace #"(^-+)|(-+$)" "")))

(defn propose-grid-via-daypart-candidates!
  "Alternative `:propose-grid` implementation for `run-quarterly!`, using the
   split round-trip (DURATION_AWARE_SCHEDULING.md §4.3, Option A) instead of
   the single monolithic `tb/propose-quarterly-grid!` call: Pass A alone
   (`tb/propose-daypart-skeleton!`) gets real daypart bounds, then for each
   block, `scheduling.candidates/propose-daypart-candidates` computes a
   duration-feasible slot-tiling menu from the catalog's runtime histogram
   BEFORE Pass B (`tb/propose-strip-fill!`) runs against that exact block —
   so the LLM has real per-daypart inventory to work from, not just the
   whole-catalog shape.

   Has the same calling contract as `tb/propose-quarterly-grid!` (a function
   of `[tunabrain opts] -> {:grid_id :grid :skeleton :warnings ...}`), so it's
   a drop-in for `run-quarterly!`'s `:propose-grid` component —
   opt in explicitly (`:propose-grid propose-grid-via-daypart-candidates!`)
   rather than the default; per DURATION_AWARE_SCHEDULING.md §4.5 this should
   land as an additive alternative, validated against real catalogs, before
   any channel is switched over."
  [tunabrain {:keys [channel quarter year catalog-profile quarterly-theme
                     strategic-guidance broadcast-day-start default-media-id
                     cost-tier]
              :or   {broadcast-day-start "06:00" cost-tier "balanced"}}]
  (let [skeleton (:skeleton (tb/propose-daypart-skeleton!
                             tunabrain
                             {:channel channel :catalog-profile catalog-profile
                              :quarterly-theme quarterly-theme
                              :strategic-guidance strategic-guidance
                              :broadcast-day-start broadcast-day-start
                              :cost-tier cost-tier}))]
    (loop [blocks (:blocks skeleton), all-strips [], warnings []]
      (if-let [block (first blocks)]
        (let [block-candidates (candidates/propose-daypart-candidates catalog-profile block)
              block-strips     (:strips (tb/propose-strip-fill!
                                         tunabrain
                                         {:channel channel :catalog-profile catalog-profile
                                          :block block :candidates block-candidates
                                          :prior-strips all-strips :cost-tier cost-tier}))]
          (recur (rest blocks)
                 (into all-strips block-strips)
                 (cond-> warnings
                   (empty? block-strips)
                   (conj (format "Daypart '%s' returned no strips" (:name block))))))
        (let [grid {:channel (:name channel)
                    :broadcast_day_start broadcast-day-start
                    :skeleton skeleton
                    :strips all-strips
                    :default_content (when default-media-id
                                       {:media_id default-media-id :strategy "random"})}
              grid-id (format "grid-%s-%s-%s-%s"
                             (slugify (:name channel)) quarter year
                             (subs (str (random-uuid)) 0 8))]
          (log/info "grid proposed via daypart candidates"
                    {:channel (:name channel) :quarter quarter :year year
                     :strips (count all-strips) :dayparts (count (:blocks skeleton))
                     :warnings (count warnings)})
          {:grid_id grid-id
           :status  (if (seq warnings) "partial" "success")
           :grid    grid
           :skeleton skeleton
           :warnings warnings})))))

(defn- content-category
  "The bare category of a `random:<category>` Content (e.g. \"sitcom\"), or nil
   when the content isn't a random pool."
  [content]
  (let [mid (:media_id content)]
    (when (and (string? mid) (str/starts-with? mid "random:"))
      (subs mid (count "random:")))))

(defn sanitize-dead-random-strips
  "Deterministic freeze-time guard for the hallucinated-tag class (handoff Bug #2):
   after the LLM's repair budget is spent, a `random:<category>` strip whose
   category names no tag in `profile` still resolves to an empty Pseudovision
   collection — dead air at playout. Rewrite each such strip's content to the
   grid's own `:default_content` (the channel's fallback pool) so the time keeps
   real programming instead of going dark. Uses feasibility's own
   `known-random-category?` predicate, so it only ever touches strips the checker
   would also flag; every other strip is returned untouched, and coverage is
   never dropped. When the grid has no usable default (or the default is itself a
   dead pool) the strip is left as-is — the feasibility status already records
   the shortfall and the weekly WARN still catches it. Fail-safe: any error
   returns the grid unchanged. Returns `{:grid :rewritten}` (`:rewritten` = the
   ids of the strips that were swapped)."
  [grid profile]
  (try
    (let [default-content (:default_content grid)
          default-cat     (content-category default-content)
          default-usable? (boolean
                           (and (map? default-content)
                                (:media_id default-content)
                                (or (nil? default-cat)
                                    (feasibility/known-random-category? profile default-cat))))
          rewritten       (atom [])
          fix-strip       (fn [{:keys [content] :as strip}]
                            (let [cat (content-category content)]
                              (if (and cat
                                       (not (feasibility/known-random-category? profile cat))
                                       default-usable?)
                                (do (swap! rewritten conj (:strip_id strip))
                                    (assoc strip :content default-content))
                                strip)))
          grid'           (cond-> grid
                            (seq (:strips grid)) (update :strips (fn [ss] (mapv fix-strip ss))))]
      (when (seq @rewritten)
        (log/warn "freeze guard rewrote dead random:<category> strips to default_content"
                  {:channel (:channel grid) :rewritten @rewritten}))
      {:grid grid' :rewritten @rewritten})
    (catch Exception e
      (log/warn e "sanitize-dead-random-strips failed; freezing grid unchanged")
      {:grid grid :rewritten []})))

(defn ensure-collection!
  "Finds an existing Pseudovision collection named `(:name spec)`, or creates
   it from `spec` (a native-schedule/content-sources entry: `{:kind :show
   :show-id ..}` or `{:kind :category :category .. :channel-tag ..}`).
   Collections are named deterministically (auto:series:<id>,
   auto:category:<cat>:<channel-tag>) precisely so re-freezing a grid finds
   and reuses the same collection instead of accumulating duplicates.
   Returns the collection id."
  [pv-config spec]
  (let [existing (some #(when (= (:name spec) (:name %)) %) (pv/get-collections pv-config))]
    (or (:id existing)
        (:id (pv/create-collection!
              pv-config
              {:name (:name spec)
               :kind "smart"
               :config {:query (case (:kind spec)
                                 :show     {:show-id (:show-id spec)}
                                 :category {:category (:category spec)
                                            :channel-tag (:channel-tag spec)})}})))))

(defn sync-native-schedule!
  "Translates a frozen Grid to Pseudovision's native schedule/slot model
   (scheduling.native-schedule/->schedule) and syncs it to the channel:
   ensures every strip's content-source collection exists, creates the
   schedule + its slots, attaches it to the channel's playout (replacing any
   previously auto-synced schedule so re-freezing doesn't accumulate
   orphans), and triggers a playout rebuild. `channel-tag` scopes
   `random:<category>` strips to this channel's own mapped media — same
   value as `catalog-tag` passed to `run-quarterly!`.

   Best-effort: a failure here does not un-freeze the grid (the grid is
   already durable in Tunarr Scheduler's own storage); callers should log
   and surface it without failing the whole quarterly run.

   Returns `{:schedule-id :slot-count :warnings}`."
  [pv-config pv-channel-id grid channel-tag & {:keys [schedule-name]}]
  (let [sources (native/content-sources grid channel-tag)
        source-id-by-name (into {}
                                 (map (fn [spec] [(:name spec) (ensure-collection! pv-config spec)]))
                                 sources)
        {:keys [name slots warnings]} (native/->schedule
                                       grid channel-tag source-id-by-name
                                       :schedule-name schedule-name)
        prior-schedule-id (:schedule-id (pv/get-playout pv-config pv-channel-id))
        sched   (pv/create-schedule! pv-config {:name name})
        sched-id (:id sched)]
    (doseq [[idx slot] (map-indexed vector slots)]
      (pv/add-slot! pv-config sched-id (assoc slot :slot-index idx)))
    (pv/attach-schedule! pv-config pv-channel-id sched-id)
    (pv/rebuild-playout! pv-config pv-channel-id {:from "now" :horizon 14})
    (when (and prior-schedule-id (not= prior-schedule-id sched-id))
      (try (pv/delete-schedule! pv-config prior-schedule-id)
           (catch Exception e
             (log/warn e "failed to delete prior auto-synced schedule"
                       {:prior-schedule-id prior-schedule-id}))))
    (log/info "native schedule synced"
              {:pv-channel-id pv-channel-id :schedule-id sched-id
               :slot-count (count slots) :warnings (count warnings)})
    {:schedule-id sched-id :slot-count (count slots) :warnings warnings}))

(defn- settle-feasibility
  "Run the propose→check→repair loop to a settled grid: returns
   `[grid report repairs]` where `grid` is the last grid checked and `report`
   its feasibility. Stops when the grid is `ok` or `:max-repairs` is reached
   (freezing best-effort even if still blocked, exactly as before)."
  [tunabrain repair-grid channel-spec profile grid0 hstart hend max-repairs cost-tier]
  (loop [grid grid0, repairs 0]
    (let [report (feasibility/check grid profile (str hstart) (str hend))]
      (if (or (= "ok" (:overall_status report)) (>= repairs max-repairs))
        [grid report repairs]
        (let [repaired (repair-grid
                        tunabrain
                        {:channel channel-spec :catalog-profile profile
                         :current-grid grid :feasibility-report report
                         :cost-tier cost-tier})]
          (log/info "repairing quarterly grid"
                    {:channel (:name channel-spec) :round (inc repairs)
                     :status (:overall_status report)})
          (recur (:grid repaired) (inc repairs)))))))

(defn run-quarterly!
  "Propose → check → repair → [review] → freeze a quarterly Grid for one channel.
   Bounds the feasibility repair loop at `:max-repairs` (default 3). When a
   `:review-schedule` component is supplied, ALSO runs the taste critique loop
   (review → revise → re-review, bounded at `:max-reviews`, default 2) on the
   feasible grid before freezing — see `scheduling.review`. Freezes best-effort
   with the final feasibility status attached, and — when `:pv-channel-id` is
   supplied — syncs the frozen grid onto Pseudovision's native schedule/slot
   model (`sync-native-schedule!`). Returns the stored grid record plus
   `:feasibility-status`, `:repairs`, `:native-sync` (when synced), and
   `:review` (when reviewed).

   The review loop is opt-in: with no `:review-schedule` component the behaviour
   is byte-for-byte the pre-review propose→check→repair→freeze→sync flow."
  [{:keys [executor tunabrain pv-config fetch-profile propose-grid repair-grid
           sync-schedule review-schedule revise-schedule]
    :or   {fetch-profile integ/fetch-catalog-profile
           propose-grid  tb/propose-quarterly-grid!
           repair-grid   tb/repair-quarterly-grid!
           sync-schedule sync-native-schedule!}}
   channel-spec quarter year
   & {:keys [cost-tier default-media-id max-repairs max-reviews catalog-tag pv-channel-id]
      :or   {cost-tier "balanced" max-repairs 3 max-reviews 2}}]
  (let [channel        (:name channel-spec)
        channel-uuid   (:uuid channel-spec)
        profile        (fetch-profile pv-config {:channel channel :tag catalog-tag})
        g              (guidance executor channel-uuid)
        [hstart hend]  (plans/quarter-range quarter year)
        proposed       (propose-grid
                        tunabrain
                        {:channel channel-spec :quarter quarter :year year
                         :catalog-profile profile
                         :quarterly-theme (:quarterly_theme g)
                         :strategic-guidance (:strategic_guidance g)
                         :default-media-id default-media-id
                         :cost-tier cost-tier})
        grid-id        (:grid_id proposed)
        [grid0 report0 repairs] (settle-feasibility tunabrain repair-grid channel-spec
                                                    profile (:grid proposed) hstart hend
                                                    max-repairs cost-tier)
        ;; Opt-in taste critique on the feasible grid, before freeze/sync.
        review-result  (when review-schedule
                         (review/run-review-loop
                          {:tunabrain tunabrain :channel-spec channel-spec :profile profile
                           :skeleton (or (:skeleton (:grid proposed)) (:skeleton proposed))
                           :grid grid0 :hstart hstart :hend hend
                           :review-fn review-schedule :revise-fn revise-schedule
                           :cost-tier cost-tier :max-reviews max-reviews}))
        reviewed-grid  (if review-result (:grid review-result) grid0)
        ;; A taste revision can change the grid, so the stored feasibility
        ;; snapshot must reflect the reviewed grid, not the pre-review one.
        ;; (Deterministic + cheap; only re-run when review actually ran.)
        report         (if review-result
                         (feasibility/check reviewed-grid profile (str hstart) (str hend))
                         report0)
        ;; Post-feasibility, pre-freeze finishing passes on the final grid:
        ;;  1. sanitize — neutralise any dead random:<category> the repair loop
        ;;     couldn't fix, so the frozen grid (and the native sync + weekly
        ;;     expansion built from it) never programs an empty pool.
        ;;  2. label — fill show titles from the CatalogProfile we already have
        ;;     so the frozen grid is self-describing in operator views;
        ;;     display-only, never affecting the check or playout.
        {sanitized :grid} (sanitize-dead-random-strips reviewed-grid profile)
        labeled        (integ/label-grid-content sanitized profile)
        stored         (storage/freeze-grid! executor channel-uuid quarter year labeled
                                             :grid-id grid-id :feasibility report)
        sync-result    (when pv-channel-id
                         (try
                           {:ok (sync-schedule pv-config pv-channel-id labeled catalog-tag)}
                           (catch Exception e
                             (log/error e "native schedule sync failed"
                                        {:channel channel :pv-channel-id pv-channel-id})
                             {:error (ex-message e)})))]
    (log/info "quarterly grid frozen"
              {:channel channel :channel-uuid channel-uuid :quarter quarter :year year
               :status (:overall_status report) :repairs repairs
               :reviewed (boolean review-result)
               :review-verdict (:verdict (:review review-result))})
    (cond-> (assoc stored :feasibility-status (:overall_status report) :repairs repairs)
      sync-result   (assoc :native-sync sync-result)
      review-result (assoc :review (:review review-result)
                           :reviews (:reviews review-result)))))

(defn run-monthly!
  "Ask Tunabrain for sparse monthly Overrides against the channel's frozen grid
   and store them. An empty override list is normal. Throws if no grid is frozen
   for the month's quarter."
  [{:keys [executor tunabrain pv-config fetch-profile propose-overrides]
    :or   {fetch-profile     integ/fetch-catalog-profile
           propose-overrides tb/propose-monthly-overrides!}}
   channel-spec month
   & {:keys [cost-tier catalog-tag] :or {cost-tier "balanced"}}]
  (let [channel     (:name channel-spec)
        channel-uuid (:uuid channel-spec)
        as-of       (str month "-01")
        quarter     (plans/quarter-of as-of)
        year        (plans/year-of as-of)
        grid-record (storage/current-grid executor channel-uuid quarter year)]
    (when (nil? grid-record)
      (throw (ex-info "no frozen grid to base monthly overrides on"
                      {:channel channel :channel-uuid channel-uuid
                       :month month :quarter quarter :year year})))
    (let [profile (fetch-profile pv-config {:channel channel :tag catalog-tag})
          g       (guidance executor channel-uuid)
          resp    (propose-overrides
                   tunabrain
                   {:channel channel-spec :month month :grid (:grid grid-record)
                    :catalog-profile profile
                    :monthly-theme (:monthly_theme g)
                    :planned-events (:planned_events g)
                    :strategic-guidance (:strategic_guidance g)
                    :cost-tier cost-tier})
          stored  (storage/store-overrides! executor channel-uuid month (:overrides resp)
                                            :overrides-id (:overrides_id resp))]
      (log/info "monthly overrides stored"
                {:channel channel :channel-uuid channel-uuid :month month
                 :count (count (:overrides resp))})
      stored)))
