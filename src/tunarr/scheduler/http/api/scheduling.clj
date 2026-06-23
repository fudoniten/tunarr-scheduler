(ns tunarr.scheduler.http.api.scheduling
  "HTTP handlers for periodic scheduling tasks.

   These endpoints are intended to be triggered by Kubernetes CronJobs (see
   deploy/k8s) rather than an in-process scheduler. Each runs the corresponding
   task against the live system components and returns a summary."
  (:require [taoensso.timbre :as log]
            [tunarr.scheduler.scheduling.tasks :as tasks]))

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
        {:status 500 :body {:error (.getMessage e)}}))))

(defn weekly-handler
  "POST /api/scheduling/weekly — re-apply schedule templates to every channel."
  [ctx]
  (fn [_]
    (try
      {:status 200 :body {:task "weekly" :results (tasks/run-weekly! ctx)}}
      (catch Exception e
        (log/error e "weekly scheduling task failed")
        {:status 500 :body {:error (.getMessage e)}}))))

(defn monthly-handler
  "POST /api/scheduling/monthly — generate a monthly strategy.
   Optional ?commit=true|false (default true) controls auto-apply."
  [ctx]
  (fn [req]
    (try
      (let [commit? (get-in req [:parameters :query :commit] true)
            s       (tasks/run-monthly! ctx :commit? commit?)]
        {:status 200 :body {:task "monthly" :committed commit? :strategy s}})
      (catch Exception e
        (log/error e "monthly scheduling task failed")
        {:status 500 :body {:error (.getMessage e)}}))))

(defn quarterly-handler
  "POST /api/scheduling/quarterly — generate a quarterly strategy.
   Optional ?commit=true|false (default true) controls auto-apply."
  [ctx]
  (fn [req]
    (try
      (let [commit? (get-in req [:parameters :query :commit] true)
            s       (tasks/run-quarterly! ctx :commit? commit?)]
        {:status 200 :body {:task "quarterly" :committed commit? :strategy s}})
      (catch Exception e
        (log/error e "quarterly scheduling task failed")
        {:status 500 :body {:error (.getMessage e)}}))))
