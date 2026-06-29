(ns tunarr.scheduler.http.api.scheduling
  "HTTP handlers for periodic scheduling tasks.

   These endpoints are intended to be triggered by Kubernetes CronJobs (see
   deploy/k8s) rather than an in-process scheduler. Each runs the corresponding
   task against the live system components and returns a per-channel summary.

   Quarterly and monthly tasks are submitted as async jobs (202 + job ID)
   because they make heavy LLM calls via Tunabrain that can take several
   minutes per channel."
  (:require [taoensso.timbre :as log]
            [tunarr.scheduler.scheduling.tasks :as tasks]
            [tunarr.scheduler.jobs.runner :as jobs]
            [tunarr.scheduler.http.util :as util]))

(defn daily-handler
  "POST /api/scheduling/daily — extend the playout horizon for every channel.
   Optional ?horizon=N (days, default 14)."
  [ctx]
  (fn [req]
    (try
      (let [horizon (get-in req [:parameters :query :horizon] 14)
            results (tasks/run-daily! ctx :horizon horizon)]
        {:status 200 :body {:task "daily" :horizon horizon :results results}})
      (catch Exception e
        (log/error e "daily scheduling task failed")
        {:status 500 :body {:error (util/error-message e)}}))))

(defn weekly-handler
  "POST /api/scheduling/weekly — expand each channel's grid + overrides for the
   coming week and push the DailySlots to Pseudovision."
  [ctx]
  (fn [_]
    (try
      {:status 200 :body {:task "weekly" :results (tasks/run-weekly! ctx)}}
      (catch Exception e
        (log/error e "weekly scheduling task failed")
        {:status 500 :body {:error (util/error-message e)}}))))

(defn monthly-handler
  "POST /api/scheduling/monthly — propose + store sparse monthly overrides for
   every channel against their frozen grids. Returns 202 with a job ID."
  [ctx]
  (fn [_]
    (let [job (jobs/submit-job!
               (:job-runner ctx)
               {:type :scheduling/monthly}
               (fn [_report-progress]
                 (tasks/run-monthly! ctx)))]
      {:status 202 :body {:task "monthly" :job job}})))

(defn quarterly-handler
  "POST /api/scheduling/quarterly — propose → check → repair → freeze the
   quarterly grid for every channel. Returns 202 with a job ID."
  [ctx]
  (fn [_]
    (let [job (jobs/submit-job!
               (:job-runner ctx)
               {:type :scheduling/quarterly}
               (fn [_report-progress]
                 (tasks/run-quarterly! ctx)))]
      {:status 202 :body {:task "quarterly" :job job}})))
