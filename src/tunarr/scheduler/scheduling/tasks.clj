(ns tunarr.scheduler.scheduling.tasks
  "Scheduling tasks invoked on a cadence by external triggers.

   These were formerly driven by an in-process cron loop. The service runs in
   Kubernetes, so scheduling is delegated to k8s CronJobs that POST to the
   HTTP API (see tunarr.scheduler.http.api.scheduling and deploy/k8s). Each
   function here operates on the live system components passed in via the
   handler context.

   • run-daily!     — extend the playout horizon for every channel
   • run-weekly!    — re-apply schedule templates to every channel
   • run-monthly!   — generate (and optionally apply) a monthly strategy
   • run-quarterly! — generate (and optionally apply) a quarterly strategy"
  (:require [taoensso.timbre :as log]
            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.scheduling.templates :as templates]
            [tunarr.scheduler.scheduling.strategy :as strategy]
            [tunarr.scheduler.backends.pseudovision.client :as pv]))

(def ^:private default-horizon 14)

(defn run-daily!
  "Extend the playout horizon for every channel. Uses Pseudovision's
   rebuild-horizon endpoint so the cursor (and sequential episode positions)
   are preserved.

   Channels are matched to their integer Pseudovision id by UUID, the same way
   templates/apply-templates-to-channels! does.

   Returns a map of channel-key → :ok / :no-channel-id / :not-found / {:error …}."
  [{:keys [pseudovision channels]} & {:keys [horizon] :or {horizon default-horizon}}]
  (log/info "task: daily horizon extension" {:horizon horizon})
  (let [pv-channels (pv/list-channels pseudovision)
        uuid->pv-id (into {} (map (fn [ch] [(str (:channels/uuid ch)) (:channels/id ch)]))
                          pv-channels)]
    (into {}
          (for [[channel-key channel-cfg] channels]
            (let [cfg-uuid (get channel-cfg ::media/channel-id)
                  pv-id    (get uuid->pv-id (str cfg-uuid))]
              [channel-key
               (cond
                 (not cfg-uuid) :no-channel-id
                 (not pv-id)    :not-found
                 :else
                 (try
                   (pv/rebuild-playout! pseudovision pv-id {:from "horizon" :horizon horizon})
                   :ok
                   (catch Exception e
                     (log/error e "task: failed to extend horizon"
                                {:channel channel-key :channel-id pv-id})
                     {:error (.getMessage e)})))])))))

(defn run-weekly!
  "Re-apply schedule templates to all channels. Picks up template/seasonal
   changes without resetting episode positions. Returns the per-channel result
   map from templates/apply-templates-to-channels!."
  [{:keys [pseudovision channels]}]
  (log/info "task: weekly template re-application")
  (templates/apply-templates-to-channels! pseudovision channels))

(defn- run-strategy!
  "Generate a strategy for `period`, optionally applying it. Returns the
   resulting strategy map (applied when commit? is true)."
  [{:keys [llm channels catalog]} period commit?]
  (let [executor (:executor catalog)
        s        (strategy/generate-strategy! executor llm channels period)]
    (if commit?
      (strategy/apply-strategy! executor (:id s))
      s)))

(defn run-monthly!
  "Generate a monthly strategy. When commit? is true (default) the strategy is
   applied immediately; otherwise it is left as a draft for review."
  [ctx & {:keys [commit?] :or {commit? true}}]
  (log/info "task: monthly strategy generation" {:commit? commit?})
  (run-strategy! ctx :monthly commit?))

(defn run-quarterly!
  "Generate a quarterly strategy. See run-monthly! for commit? semantics."
  [ctx & {:keys [commit?] :or {commit? true}}]
  (log/info "task: quarterly strategy generation" {:commit? commit?})
  (run-strategy! ctx :quarterly commit?))
