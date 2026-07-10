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
   human-readable identity and storage needs an unchanging identifier."
  [cfg]
  {:name        (::media/channel-fullname cfg)
   :description (::media/channel-description cfg)
   :uuid        (::media/channel-uuid cfg)})

(defn- channel-storage-uuid
  "The canonical TS `channel.id` UUID for a channel's config entry, used
   as the storage key for grids/overrides/guidance. Returns the value
   verbatim from the config; if the entry is missing the field, returns
   nil (and the storage call will fail loudly with a useful error)."
  [cfg]
  (::media/channel-uuid cfg))

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
            (let [channel-uuid (channel-storage-uuid cfg)
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
   channel's frozen grid for the current month. Returns channel-key → stored
   overrides record / {:error …} (a missing grid surfaces as an error)."
  [{:keys [channels] :as ctx}]
  (log/info "task: monthly overrides")
  (let [comps (components ctx)
        month (plans/month-of (plans/today))]
    (into {}
          (for [[channel-key cfg] channels]
            [channel-key
             (try (orch/run-monthly! comps (channel-spec cfg) month
                                     :catalog-tag (channel-catalog-tag channel-key))
                  (catch Exception e
                    (log/error e "task: monthly overrides failed" {:channel channel-key})
                    {:error (util/error-message e)}))]))))

(defn run-quarterly!
  "Propose → check → repair → freeze the quarterly grid for every channel for the
   current quarter. Returns channel-key → frozen grid record (with
   :feasibility-status) / {:error …}."
  [{:keys [channels] :as ctx}]
  (log/info "task: quarterly grid generation")
  (let [comps   (components ctx)
        today   (plans/today)
        quarter (plans/quarter-of today)
        year    (plans/year-of today)]
    (into {}
          (for [[channel-key cfg] channels]
            [channel-key
             (try (orch/run-quarterly! comps (channel-spec cfg) quarter year
                                       :catalog-tag (channel-catalog-tag channel-key))
                  (catch Exception e
                    (log/error e "task: quarterly grid failed" {:channel channel-key})
                    {:error (util/error-message e)}))]))))
