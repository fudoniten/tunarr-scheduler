(ns tunarr.scheduler.http.api.scheduling
  "HTTP handlers for periodic scheduling tasks.

   These endpoints are intended to be triggered by Kubernetes CronJobs (see
   deploy/k8s) rather than an in-process scheduler. Each runs the corresponding
   task against the live system components and returns a per-channel summary.

   Quarterly and monthly tasks are submitted as async jobs (202 + job ID)
   because they make heavy LLM calls via Tunabrain that can take several
   minutes per channel.

   All endpoints accept an optional repeatable ?channel=key query parameter to
   limit the run to specific channels. When omitted, all configured channels
   are processed."
  (:require [taoensso.timbre :as log]
            [tunarr.scheduler.scheduling.tasks :as tasks]
            [tunarr.scheduler.jobs.runner :as jobs]
            [tunarr.scheduler.http.util :as util]))

(defn- filter-channels
  "Return ctx with :channels filtered to only the requested keys, or unchanged
   when channel-param is nil."
  [ctx channel-param]
  (if channel-param
    (let [ks (if (string? channel-param) #{channel-param} (set channel-param))]
      (update ctx :channels select-keys ks))
    ctx))

(defn- parse-channel-param [req]
  (get-in req [:parameters :query :channel]))

(defn daily-handler
  "POST /api/scheduling/daily — extend the playout horizon for every channel.
   Optional ?horizon=N (days, default 14). Optional ?channel=key to limit."
  [ctx]
  (fn [req]
    (try
      (let [horizon (get-in req [:parameters :query :horizon] 14)
            ctx'    (filter-channels ctx (parse-channel-param req))
            results (tasks/run-daily! ctx' :horizon horizon)]
        {:status 200 :body {:task "daily" :horizon horizon :results results}})
      (catch Exception e
        (log/error e "daily scheduling task failed")
        {:status 500 :body {:error (util/error-message e)}}))))

(defn weekly-handler
  "POST /api/scheduling/weekly — expand each channel's grid + overrides for the
   coming week and push the DailySlots to Pseudovision.
   Optional ?channel=key to limit."
  [ctx]
  (fn [req]
    (try
      (let [ctx' (filter-channels ctx (parse-channel-param req))]
        {:status 200 :body {:task "weekly" :results (tasks/run-weekly! ctx')}})
      (catch Exception e
        (log/error e "weekly scheduling task failed")
        {:status 500 :body {:error (util/error-message e)}}))))

(defn monthly-handler
  "POST /api/scheduling/monthly — propose + store sparse monthly overrides for
   every channel against their frozen grids. Returns 202 with a job ID.
   Optional ?channel=key to limit."
  [ctx]
  (fn [req]
    (let [ctx' (filter-channels ctx (parse-channel-param req))
          job  (jobs/submit-job!
                (:job-runner ctx)
                {:type :media/scheduling-monthly}
                (fn [_report-progress]
                  (tasks/run-monthly! ctx')))]
      {:status 202 :body {:task "monthly" :job job}})))

(defn quarterly-handler
  "POST /api/scheduling/quarterly — propose → check → repair → freeze the
   quarterly grid for every channel. Returns 202 with a job ID.
   Optional ?channel=key to limit."
  [ctx]
  (fn [req]
    (let [ctx' (filter-channels ctx (parse-channel-param req))
          job  (jobs/submit-job!
                (:job-runner ctx)
                {:type :media/scheduling-quarterly}
                (fn [_report-progress]
                  (tasks/run-quarterly! ctx')))]
      {:status 202 :body {:task "quarterly" :job job}})))
