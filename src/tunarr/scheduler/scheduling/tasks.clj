(ns tunarr.scheduler.scheduling.tasks
  "Scheduling tasks invoked on a cadence by external triggers (k8s CronJobs that
  POST to the HTTP API; see tunarr.scheduler.http.api.scheduling and
  deploy/k8s). Each function operates on the live system components in the
  handler context.

  These drive the layered-grid pipeline:
  • run-daily!     — extend the Pseudovision playout horizon for every channel
  • run-weekly!    — expand each channel's frozen grid + overrides for the
                     coming week and push the DailySlots to Pseudovision
                     (deterministic; no Tunabrain call)
  • run-monthly!   — propose + store sparse monthly overrides per channel
  • run-quarterly! — propose → feasibility → repair → freeze the quarterly grid
                     per channel

  Channels come from config (a map of channel-key → {::media/channel-fullname
  ::media/channel-description ::media/channel-id ::media/channel-uuid}). The
  display name is what Tunabrain sees and what the inner Grid.content carries
  (it's the channel *content* the LLM reasons about). The TS `channel.id`
  UUID (carried in config as `::media/channel-uuid`) is the stable storage key
  for the layered-grid system (grids, overrides, channel_guidance). The PV
  UUID (`::media/channel-id`) is the integer/UUID PV uses for the daily-slots
  push and is a separate value from the TS storage UUID."
  (:require [taoensso.timbre :as log]
            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.scheduling.integration :as integ]
            [tunarr.scheduler.scheduling.orchestration :as orch]
            [tunarr.scheduler.scheduling.plans :as plans]
            [tunarr.scheduler.scheduling.storage :as storage]
            [tunarr.scheduler.backends.pseudovision.client :as pv]
            [tunarr.scheduler.http.util :as util]))

(def ^:private default-horizon 14)

(defn- pv-config
  "Unwrap the raw Pseudovision config (with :base-url) from the backend record."
  [pseudovision]
  (if (:base-url pseudovision) pseudovision (pv/get-config pseudovision)))

(defn- channel-spec
  "The Tunabrain-facing channel spec ({:name :description}) plus the
   canonical storage UUID. The `uuid` is the stable storage key for the
   layered-grid system; `name` is the display name the LLM reasons
   about. The two are distinct because the LLM cares about
   human-readable identity and storage needs an unchanging identifier.

   Mirrors the read-side `channel-storage-uuid` resolver: prefers
   `::media/channel-uuid` from the config, and falls back to a
   `channel` table lookup by `full_name` (case-insensitive) or
   `name` (the config-key slug) when the configmap doesn't carry the
   key. The DB row is the source of truth in that case. `executor`
   is the SQL executor used for the fallback; nil disables the
   fallback (the caller will see `:uuid nil` and is expected to
   surface the error, same as before this change)."
  [executor cfg]
  {:name        (::media/channel-fullname cfg)
   :description (::media/channel-description cfg)
   :uuid        (or (::media/channel-uuid cfg)
                    (let [name-or-slug (::media/channel-fullname cfg)]
                      (when (and executor name-or-slug)
                        (storage/find-channel-id executor name-or-slug))))})

(defn- channel-storage-uuid
  "The canonical TS `channel.id` UUID for a channel's config entry, used
   as the storage key for grids/overrides/guidance. Reads
   `::media/channel-uuid` from the config first; falls back to a
   `channel` table lookup by `full_name` (case-insensitive) or
   `name` (the config-key slug) when the configmap doesn't carry
   `::media/channel-uuid` — the DB row is the source of truth in that
   case. The fallback keeps the run-weekly!/run-monthly!/run-quarterly!
   paths working without a configmap rollout."
  [executor cfg]
  (or (::media/channel-uuid cfg)
      (let [name-or-slug (::media/channel-fullname cfg)]
        (when name-or-slug
          (storage/find-channel-id executor name-or-slug)))))

(defn- channel-catalog-tag
  "The catalog-aggregate filter tag for a channel, `channel:<slug>`. In
   Pseudovision a media item belongs to a channel by carrying this tag (synced
   from the `channel` dimension by media.pseudovision-sync), so scoping the
   aggregate by this tag is what actually narrows the profile to the channel's
   pool. `channel-key` is the channel's config key — the slug (e.g.
   :goldenreels), which is the channel dimension's value — not the display-name
   fullname."
  [channel-key]
  (str "channel:" (name channel-key)))

(defn- uuid->pv-id
  "Map of Pseudovision channel UUID → integer id. The /api/channels records use
   plain :uuid / :id keys (we also accept table-qualified :channels/* keys
   defensively, in case the API is ever served with namespaced keys)."
  [pv-conf]
  (into {} (map (fn [ch]
                  [(str (or (:uuid ch) (:channels/uuid ch)))
                   (or (:id ch) (:channels/id ch))]))
        (pv/list-channels pv-conf)))

(defn run-daily!
  "Extend the playout horizon for every channel via Pseudovision's
   rebuild-horizon endpoint (preserves the cursor and sequential positions).
   Returns channel-key → :ok / :no-channel-id / :not-found / {:error …}."
  [{:keys [pseudovision channels]} & {:keys [horizon] :or {horizon default-horizon}}]
  (log/info "task: daily horizon extension" {:horizon horizon})
  (let [conf  (pv-config pseudovision)
        index (uuid->pv-id conf)]
    (into {}
          (for [[channel-key cfg] channels]
            (let [pv-id (get index (str (::media/channel-id cfg)))]
              [channel-key
               (cond
                 (not (::media/channel-id cfg)) :no-channel-id
                 (not pv-id)                    :not-found
                 :else
                 (try
                   (pv/rebuild-playout! conf pv-id {:from "horizon" :horizon horizon})
                   :ok
                   (catch Exception e
                     (log/error e "task: failed to extend horizon"
                                {:channel channel-key :channel-id pv-id})
                     {:error (util/error-message e)})))])))))

(defn run-weekly!
  "Expand each channel's frozen grid + overrides for the coming week and push the
  resulting DailySlots to Pseudovision. Deterministic — no Tunabrain call.
  Returns channel-key → publish result / :no-channel-id / :not-found / {:error}."
  [{:keys [pseudovision channels catalog]}]
  (log/info "task: weekly expand + publish")
  (let [executor (:executor catalog)
        conf     (pv-config pseudovision)
        index    (uuid->pv-id conf)
        start    (plans/today)
        end      (.plusDays start 7)]
    (into {}
          (for [[channel-key cfg] channels]
            (let [channel-uuid (channel-storage-uuid executor cfg)
                  pv-id        (get index (str (::media/channel-id cfg)))]
              [channel-key
               (cond
                 (not (::media/channel-id cfg)) :no-channel-id
                 (not pv-id)                    :not-found
                 (not channel-uuid)             :no-channel-uuid
                 :else
                 (try
                    (integ/publish-week! executor conf channel-uuid pv-id (str start) (str end)
                                         :channel-tag (channel-catalog-tag channel-key))
                   (catch Exception e
                     (log/error e "task: weekly publish failed" {:channel channel-key})
                     {:error (util/error-message e)})))])))))

(defn- components [{:keys [tunabrain pseudovision catalog]}]
  {:executor (:executor catalog)
   :tunabrain tunabrain
   :pv-config (pv-config pseudovision)})

(defn run-monthly!
  "Propose + store sparse monthly overrides for every channel, against the
   channel's frozen grid for a month.

   `:date` (optional, a 'YYYY-MM-DD' string or LocalDate) selects WHICH month —
   the overrides are stored for the month of that date; defaults to today. Pass
   a date in NEXT month (e.g. run a week before month-end) to pre-generate the
   coming month's overrides. Unlike quarterly, this is always safe to run ahead:
   overrides are only stored, never attached to a live playout — the weekly
   expander applies them per-date once that month arrives, so there is no early
   cutover and no current-month guard is needed.

   Returns channel-key → stored overrides record / {:error …} (a missing grid
   surfaces as an error)."
  [{:keys [channels] :as ctx} & {:keys [date]}]
  (let [comps (components ctx)
        month (plans/month-of (or date (plans/today)))]
    (log/info "task: monthly overrides" {:month month})
    (into {}
          (for [[channel-key cfg] channels]
            [channel-key
             (try (orch/run-monthly! comps (channel-spec (:executor comps) cfg) month
                                     :catalog-tag (channel-catalog-tag channel-key))
                  (catch Exception e
                    (log/error e "task: monthly overrides failed" {:channel channel-key})
                    {:error (util/error-message e)}))]))))

(defn run-quarterly!
  "Propose → check → repair → freeze the quarterly grid for every channel, and —
   for the CURRENT quarter only — sync it onto Pseudovision's native
   schedule/slot model (orch/sync-native-schedule!, via :pv-channel-id).

   `:date` (optional, a 'YYYY-MM-DD' string or LocalDate) selects WHICH quarter
   to (re)generate: the grid's quarter and year are taken from that date.
   Defaults to today. Pass a date inside the UPCOMING quarter (the point of
   running a week or so before the boundary) to pre-generate the next quarter's
   grid ahead of time.

   Freeze vs. sync — an important distinction:

   • Generating the CURRENT quarter (target quarter == today's) freezes the grid
     AND syncs it to the live playout. The sync EXTENDS the timeline rather than
     resetting it (see orch/sync-native-schedule!), so on the boundary the new
     grid takes over at the end of already-published programming instead of
     cutting over — no obsoleted guide.

   • Generating any OTHER quarter (a future quarter, e.g. pre-generating next
     quarter early, or backfilling a past one) FREEZES the grid only and leaves
     the live playout untouched. This is deliberate: the native schedule has no
     notion of an effective date — attaching a future quarter's grid now would
     make it air immediately at the timeline's append edge, before that quarter
     actually begins. The frozen grid is reviewable and ready; it is applied by
     the regular current-quarter run once the boundary arrives.

   Returns channel-key → frozen grid record (with :feasibility-status and,
   when synced, :native-sync) / :no-channel-id / :not-found / {:error …}."
  [{:keys [pseudovision channels] :as ctx} & {:keys [date]}]
  (let [comps    (components ctx)
        conf     (pv-config pseudovision)
        index    (uuid->pv-id conf)
        today    (plans/today)
        target   (or date today)
        quarter  (plans/quarter-of target)
        year     (plans/year-of target)
        ;; Only the current quarter is applied to the live playout; other
        ;; quarters are frozen-only (see docstring). Sync is triggered by passing
        ;; :pv-channel-id to orch/run-quarterly!, so gate that on current?.
        current? (and (= quarter (plans/quarter-of today))
                      (= year    (plans/year-of today)))]
    (log/info "task: quarterly grid generation"
              {:date (str target) :quarter quarter :year year
               :current-quarter? current? :sync? current?})
    (into {}
          (for [[channel-key cfg] channels]
            (let [pv-id (get index (str (::media/channel-id cfg)))]
              [channel-key
               (cond
                 (not (::media/channel-id cfg)) :no-channel-id
                 (not pv-id)                    :not-found
                 :else
                 (try (orch/run-quarterly! comps (channel-spec (:executor comps) cfg) quarter year
                                           :catalog-tag (channel-catalog-tag channel-key)
                                           :pv-channel-id (when current? pv-id))
                      (catch Exception e
                        (log/error e "task: quarterly grid failed" {:channel channel-key})
                        {:error (util/error-message e)})))])))))
