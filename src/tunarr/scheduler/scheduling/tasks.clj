(ns tunarr.scheduler.scheduling.tasks
  "Scheduling tasks invoked on a cadence by external triggers (k8s CronJobs that
  POST to the HTTP API; see tunarr.scheduler.http.api.scheduling and
  deploy/k8s). Each function operates on the live system components in the
  handler context.

  These drive the layered-grid pipeline:
  • run-daily!     — extend the Pseudovision playout horizon for every channel
  • run-monthly!   — propose + store sparse monthly overrides per channel
  • run-quarterly! — propose → feasibility → repair → freeze the quarterly grid
                     per channel

  Channels come from config (a map of channel-key → {::media/channel-fullname
  ::media/channel-description ::media/channel-id ::media/channel-uuid}). The
  display name is what Tunabrain sees and what the inner Grid.content carries
  (it's the channel *content* the LLM reasons about). The TS `channel.id`
  UUID (carried in config as `::media/channel-uuid`) is the stable storage key
  for the layered-grid system (grids, overrides, channel_guidance). The PV
  UUID (`::media/channel-id`) identifies the channel to PV and is a separate value from the TS storage UUID."
  (:require [taoensso.timbre :as log]
            [tunarr.scheduler.media :as media]
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

(defn- daily-channel-result
  "Submit one channel's horizon rebuild to PV and wait for the job to finish.
   PV's rebuild endpoint is asynchronous (202 + job), so treating the 202 as
   success reported every channel `:ok` even when its build failed or did
   nothing. Returns `{:status \"ok\"|\"failed\"|\"timeout\" :job-id ..}` plus
   PV's job result (`:events-generated`, and `:warning` when PV skipped the
   channel) or `:error`."
  [conf pv-id horizon job-timeout-ms]
  (let [submitted (pv/rebuild-playout! conf pv-id {:from "horizon" :horizon horizon})
        job-id    (get-in submitted [:job :id])
        job       (if job-id
                    (pv/await-job! conf job-id :timeout-ms job-timeout-ms)
                    {:status "failed" :error {:message "PV returned no job id"}})
        status    (some-> (:status job) name)]
    (cond-> {:status (case status "succeeded" "ok" (or status "unknown"))
             :job-id job-id}
      (map? (:result job))         (merge (select-keys (:result job)
                                                       [:events-generated :warning]))
      (= "failed" status)          (assoc :error (or (get-in job [:error :message])
                                                     "PV job failed"))
      (= "timeout" status)         (assoc :error (str "PV job did not finish within "
                                                      (quot job-timeout-ms 60000) " min")))))

(defn run-daily!
  "Extend the playout horizon for every channel via Pseudovision's
   rebuild-horizon endpoint (preserves the cursor and sequential positions).

   Channels are rebuilt one at a time and each PV job is awaited, so the
   response says what actually happened per channel. Returns channel-key ->
   :no-channel-id / :not-found, or a map `{:status \"ok\"|\"failed\"|\"timeout\"
   :job-id .. :events-generated N :warning .. :error ..}` (see
   `daily-channel-result`)."
  [{:keys [pseudovision channels]} & {:keys [horizon job-timeout-ms]
                                      :or {horizon default-horizon
                                           job-timeout-ms (* 20 60 1000)}}]
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
                   (let [result (daily-channel-result conf pv-id horizon job-timeout-ms)]
                     (if (and (= "ok" (:status result)) (nil? (:warning result)))
                       (log/info "task: horizon extended"
                                 (assoc result :channel channel-key :channel-id pv-id))
                       (log/warn "task: horizon extension did not complete"
                                 (assoc result :channel channel-key :channel-id pv-id)))
                     result)
                   (catch Exception e
                     (log/error e "task: failed to extend horizon"
                                {:channel channel-key :channel-id pv-id})
                     {:status "failed" :error (util/error-message e)})))])))))

(defn- components [{:keys [tunabrain pseudovision catalog]}]
  {:executor (:executor catalog)
   :tunabrain tunabrain
   :pv-config (pv-config pseudovision)})

(defn run-monthly!
  "Propose + store sparse monthly overrides for every channel, against the
   channel's frozen grid for a month, then re-sync each channel's native PV
   schedule so overrides that can still air reach the playout (see
   orch/run-monthly!).

   `:date` (optional, a 'YYYY-MM-DD' string, a LocalDate, or \"next-month\")
   selects WHICH month; the overrides are stored for the month of that date.
   Defaults to today. The CronJob passes \"next-month\" on the 10th. The
   override slots are bounded to their own dates, so syncing them early is safe;
   run at least two weeks ahead, because the re-sync only extends the timeline
   past the already-built horizon.

   Returns channel-key → stored overrides record (with :native-sync when a PV
   channel was found) / {:error …} (a missing grid surfaces as an error)."
  [{:keys [channels] :as ctx} & {:keys [date]}]
  (let [comps (components ctx)
        ;; Without PV the overrides are still stored; they reach PV on the
        ;; next successful sync.
        index (try (uuid->pv-id (:pv-config comps))
                   (catch Exception e
                     (log/warn e "task: monthly could not list PV channels; skipping re-sync")
                     {}))
        today (plans/today)
        month (plans/month-of (cond
                                (= "next-month" (str date)) (.plusMonths today 1)
                                date                        date
                                :else                       today))]
    (log/info "task: monthly overrides" {:month month})
    (into {}
          (for [[channel-key cfg] channels]
            [channel-key
             (try (orch/run-monthly! comps (channel-spec (:executor comps) cfg) month
                                     :catalog-tag (channel-catalog-tag channel-key)
                                     :pv-channel-id (get index (str (::media/channel-id cfg))))
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
