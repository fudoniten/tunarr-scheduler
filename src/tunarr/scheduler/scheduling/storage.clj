(ns tunarr.scheduler.scheduling.storage
  "Persistence for the layered-grid scheduler — the system of record.

  Tunarr Scheduler holds all scheduling state (Tunabrain is stateless). This
  namespace stores the three durable artifacts:

  • A frozen **Grid** per (channel, quarter, year). Immutable once frozen:
    re-authoring inserts a new version and supersedes the prior one, rather
    than mutating in place. Carries Tunabrain's `grid_id` and, optionally, the
    FeasibilityReport it was frozen against (audit trail).
  • An **Override[]** set per (channel, month), versioned the same way.
  • A **ChannelGuidance** record per channel (operator input; no versioning).

  All three tables' `channel` column holds the **TS `channel.id` UUID**
  (e.g. 'e2d423d2-f373-49fa-8c2a-b2ea1ed8c144'), NOT the display name and
  NOT the config-key slug. The UUID is the only stable identifier across
  channel renames; see `tunarr.scheduler.scheduling.tasks/channel-spec`
  for the call-side convention and AGENTS.md pitfall 8 for the history.

  The complex artifacts are stored as JSON text (mirroring
  `tunarr.scheduler.scheduling.strategy`); the stored Grid / Override list
  round-trip exactly with the wire contracts in
  `tunarr.scheduler.scheduling.contracts`. Every public fn takes the shared SQL
  executor as its first argument."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [honey.sql.helpers :as h]
            [taoensso.timbre :as log]
            [tunarr.scheduler.scheduling.contracts :as contracts]
            [tunarr.scheduler.sql.executor :as executor])
  (:import [java.time Instant]
           [java.util UUID]))

;; ---------------------------------------------------------------------------
;; Executor helpers (same shape as scheduling.strategy)
;; ---------------------------------------------------------------------------

(defn- unwrap [op [status payload]]
  (case status
    :ok  payload
    :err (do (log/error payload (format "storage SQL %s failed" op)) (throw payload))
    (throw (ex-info "unexpected SQL executor status" {:op op :status status}))))

(defn- exec!    [ex query]   (unwrap "exec!"    (deref (executor/exec! ex query))))
(defn- fetch!   [ex query]   (unwrap "fetch!"   (deref (executor/fetch! ex query))))
(defn- tx!      [ex queries] (unwrap "tx!"      (deref (executor/exec-with-tx! ex queries))))

(defn- now-iso [] (.toString (Instant/now)))
(defn- ->json [x] (json/generate-string x))
(defn- json-> [s default] (if (some? s) (json/parse-string s true) default))

(defn- scalar
  "The single value of a one-column aggregate result, independent of how the
   driver cases the column alias (H2 uppercases, Postgres lowercases)."
  [rows]
  (some-> rows first vals first))

;; ---------------------------------------------------------------------------
;; Grids
;; ---------------------------------------------------------------------------

(defn- row->grid [row]
  (when row
    (cond-> {:id         (:grids/id row)
             :grid_id    (:grids/grid_id row)
             :channel    (:grids/channel row)
             :quarter    (:grids/quarter row)
             :year       (:grids/cal_year row)
             :version    (:grids/version row)
             :status     (:grids/status row)
             :grid       (json-> (:grids/grid row) nil)
             :created-at (:grids/created_at row)}
      (:grids/feasibility row) (assoc :feasibility (json-> (:grids/feasibility row) nil)))))

(defn- next-grid-version [ex channel quarter year]
  (inc (or (scalar (fetch! ex (-> (h/select [[:max :version] :v])
                                  (h/from :grids)
                                  (h/where [:and [:= :channel channel] [:= :quarter quarter]
                                            [:= :cal_year year]]))))
           0)))

(defn freeze-grid!
  "Persist a frozen Grid for (channel, quarter, year) as a new version, marking
   any prior versions for the same key 'superseded'. `grid` must conform to the
   Grid contract. Optional opts: `:grid-id` (Tunabrain's id) and `:feasibility`
   (the FeasibilityReport it was frozen against). Returns the stored row map."
  [ex channel quarter year grid & {:keys [grid-id feasibility]}]
  (when-let [err (contracts/humanize contracts/Grid grid)]
    (throw (ex-info "refusing to store a non-conforming Grid" {:errors err})))
  (let [version (next-grid-version ex channel quarter year)
        id      (str (UUID/randomUUID))
        created (now-iso)]
    (tx! ex [(-> (h/update :grids)
                 (h/set {:status "superseded"})
                 (h/where [:and [:= :channel channel] [:= :quarter quarter]
                           [:= :cal_year year] [:= :status "frozen"]]))
             (-> (h/insert-into :grids)
                 (h/values [{:id id :grid_id grid-id :channel channel :quarter quarter
                             :cal_year year :version version :status "frozen" :grid (->json grid)
                             :feasibility (when feasibility (->json feasibility))
                             :created_at created}]))])
    (log/info "Froze grid" {:channel channel :quarter quarter :year year :version version})
    (cond-> {:id id :grid_id grid-id :channel channel :quarter quarter :year year
             :version version :status "frozen" :grid grid :created-at created}
      feasibility (assoc :feasibility feasibility))))

(defn current-grid
  "The latest frozen Grid for (channel, quarter, year), or nil."
  [ex channel quarter year]
  (-> (fetch! ex (-> (h/select :*)
                     (h/from :grids)
                     (h/where [:and [:= :channel channel] [:= :quarter quarter]
                               [:= :cal_year year] [:= :status "frozen"]])
                     (h/order-by [:version :desc])
                     (h/limit 1)))
      first row->grid))

(defn get-grid [ex id]
  (-> (fetch! ex (-> (h/select :*) (h/from :grids) (h/where [:= :id id]))) first row->grid))

(defn list-grids
  "All grid versions, newest first. Pass :channel to filter."
  [ex & {:keys [channel]}]
  (->> (fetch! ex (cond-> (-> (h/select :*) (h/from :grids)
                              (h/order-by [:created_at :desc] [:version :desc]))
                    channel (h/where [:= :channel channel])))
       (mapv row->grid)))

;; ---------------------------------------------------------------------------
;; Overrides
;; ---------------------------------------------------------------------------

(defn- row->overrides [row]
  (when row
    {:id           (:overrides/id row)
     :overrides_id (:overrides/overrides_id row)
     :channel      (:overrides/channel row)
     :month        (:overrides/cal_month row)
     :version      (:overrides/version row)
     :status       (:overrides/status row)
     :overrides    (vec (json-> (:overrides/overrides row) []))
     :created-at   (:overrides/created_at row)}))

(defn- next-overrides-version [ex channel month]
  (inc (or (scalar (fetch! ex (-> (h/select [[:max :version] :v])
                                  (h/from :overrides)
                                  (h/where [:and [:= :channel channel] [:= :cal_month month]]))))
           0)))

(defn store-overrides!
  "Persist an Override list for (channel, month) as a new active version,
   superseding any prior active version. `overrides` must be a (possibly empty)
   vector conforming to the Override contract. Returns the stored row map."
  [ex channel month overrides & {:keys [overrides-id]}]
  (doseq [o overrides]
    (when-let [err (contracts/humanize contracts/ScheduleOverride o)]
      (throw (ex-info "refusing to store a non-conforming Override" {:errors err :override o}))))
  (let [version (next-overrides-version ex channel month)
        id      (str (UUID/randomUUID))
        created (now-iso)]
    (tx! ex [(-> (h/update :overrides)
                 (h/set {:status "superseded"})
                 (h/where [:and [:= :channel channel] [:= :cal_month month] [:= :status "active"]]))
             (-> (h/insert-into :overrides)
                 (h/values [{:id id :overrides_id overrides-id :channel channel :cal_month month
                             :version version :status "active" :overrides (->json (vec overrides))
                             :created_at created}]))])
    (log/info "Stored overrides" {:channel channel :month month :version version
                                  :count (count overrides)})
    {:id id :overrides_id overrides-id :channel channel :month month
     :version version :status "active" :overrides (vec overrides) :created-at created}))

(defn current-overrides
  "The latest active Override set for (channel, month), or nil."
  [ex channel month]
  (-> (fetch! ex (-> (h/select :*)
                     (h/from :overrides)
                     (h/where [:and [:= :channel channel] [:= :cal_month month] [:= :status "active"]])
                     (h/order-by [:version :desc])
                     (h/limit 1)))
      first row->overrides))

(defn list-overrides
  "All override sets, newest first. Pass :channel to filter."
  [ex & {:keys [channel]}]
  (->> (fetch! ex (cond-> (-> (h/select :*) (h/from :overrides)
                              (h/order-by [:created_at :desc] [:version :desc]))
                    channel (h/where [:= :channel channel])))
       (mapv row->overrides)))

;; ---------------------------------------------------------------------------
;; Per-channel operator guidance (the "manual input" surface)
;; ---------------------------------------------------------------------------

(defn- row->guidance [row]
  (when row
    {:channel            (:channel_guidance/channel row)
     :strategic_guidance (:channel_guidance/strategic_guidance row)
     :quarterly_theme    (:channel_guidance/quarterly_theme row)
     :monthly_theme      (:channel_guidance/monthly_theme row)
     :planned_events     (vec (json-> (:channel_guidance/planned_events row) []))
     :updated-at         (:channel_guidance/updated_at row)}))

(defn get-guidance
  "The operator guidance for a channel, or nil if none set."
  [ex channel]
  (-> (fetch! ex (-> (h/select :*) (h/from :channel_guidance) (h/where [:= :channel channel])))
      first row->guidance))

(defn set-guidance!
  "Upsert the operator guidance for a channel. `fields` may contain any of
   :strategic_guidance, :quarterly_theme, :monthly_theme, :planned_events
   (absent keys are left unchanged on update, default/empty on insert). Returns
   the stored guidance map."
  [ex channel fields]
  (let [updated   (now-iso)
        existing  (get-guidance ex channel)
        merged    (merge {:strategic_guidance nil :quarterly_theme nil
                          :monthly_theme nil :planned_events []}
                         (dissoc existing :channel :updated-at)
                         (select-keys fields [:strategic_guidance :quarterly_theme
                                              :monthly_theme :planned_events]))
        columns   {:strategic_guidance (:strategic_guidance merged)
                   :quarterly_theme    (:quarterly_theme merged)
                   :monthly_theme      (:monthly_theme merged)
                   :planned_events     (->json (vec (:planned_events merged)))
                   :updated_at         updated}]
    (if existing
      (exec! ex (-> (h/update :channel_guidance) (h/set columns)
                    (h/where [:= :channel channel])))
      (exec! ex (-> (h/insert-into :channel_guidance)
                    (h/values [(assoc columns :channel channel)]))))
    (log/info "Stored channel guidance" {:channel channel})
    {:channel            channel
     :strategic_guidance (:strategic_guidance merged)
     :quarterly_theme    (:quarterly_theme merged)
     :monthly_theme      (:monthly_theme merged)
     :planned_events     (vec (:planned_events merged))
     :updated-at         updated}))

(defn list-guidance
  "All channel guidance rows."
  [ex]
  (->> (fetch! ex (-> (h/select :*) (h/from :channel_guidance) (h/order-by [:channel :asc])))
       (mapv row->guidance)))

;; ---------------------------------------------------------------------------
;; Cross-cutting
;; ---------------------------------------------------------------------------

(defn planned-channels
  "Distinct channel names that have any stored grid, override set, or guidance."
  [ex]
  (->> (concat (map :channel (list-grids ex))
               (map :channel (list-overrides ex))
               (map :channel (list-guidance ex)))
       (remove nil?)
       distinct
       sort
       vec))

;; ---------------------------------------------------------------------------
;; Channel lookup (used by the read API to translate URL slugs/names to the
;; canonical channel.id UUID that the storage tables are keyed by).
;; ---------------------------------------------------------------------------

(defn find-channel-id
  "Look up the `channel.id` UUID by matching `name-or-slug` against
   `full_name` (exact or case-insensitive) or `name` (the config-key slug,
   exact match). Returns the UUID string or nil. The DB is the source of
   truth for this mapping when the configmap doesn't carry
   `::media/channel-uuid` for a given channel."
  [ex name-or-slug]
  (let [rows (-> (h/select :id)
                 (h/from :channel)
                 (h/where [:or
                           [:= :full_name name-or-slug]
                           [:= :lower :full_name (str/lower-case name-or-slug)]
                           [:= :name name-or-slug]])
                 fetch!)
        hit  (first rows)]
    (when hit
      (or (:channel/id hit) (:id hit) (some-> hit vals first)))))

(defn find-channel-full-name
  "Look up the `channel.full_name` display name by `channel.id` UUID.
   Returns the display name string or nil. Used by the read API to
   include a human-readable channel name in responses when the channel
   was resolved via the DB fallback (configmap didn't have
   `::media/channel-uuid` for it)."
  [ex channel-uuid]
  (let [rows (-> (h/select :full_name)
                 (h/from :channel)
                 (h/where [:= :id channel-uuid])
                 fetch!)
        hit  (first rows)]
    (when hit
      (or (:channel/full_name hit) (:full_name hit) (some-> hit vals first)))))
