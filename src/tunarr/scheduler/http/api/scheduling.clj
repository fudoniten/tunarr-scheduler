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
  (:require [clojure.string :as str]
            [taoensso.timbre :as log]
            [tunarr.scheduler.scheduling.tasks :as tasks]
            [tunarr.scheduler.jobs.runner :as jobs]
            [tunarr.scheduler.http.util :as util]))

(defn- requested-keys
  "Normalize the ?channel param (a string or vector of strings) to a set of
   requested channel names."
  [channel-param]
  (set (if (string? channel-param) [channel-param] channel-param)))

(defn- filter-channels
  "Return ctx with :channels filtered to only the requested channels, or
   unchanged when channel-param is nil.

   The ?channel query param arrives as a string, but channel config keys are
   keywords, so match on (name k) rather than the raw key — otherwise a string
   param never matches a keyword key, the channel map is silently emptied, and
   the task runs against zero channels (no Tunabrain call)."
  [ctx channel-param]
  (if channel-param
    (let [requested (requested-keys channel-param)]
      (update ctx :channels
              (fn [chs]
                (into {} (filter (fn [[k _]] (contains? requested (name k)))) chs))))
    ctx))

(defn- unknown-channels
  "Requested channel names that don't exist in ctx's configured :channels
   (matched by name). Returns a sorted seq, or nil when all are known."
  [ctx channel-param]
  (when channel-param
    (let [known (set (map name (keys (:channels ctx))))]
      (seq (sort (remove known (requested-keys channel-param)))))))

(defn- unknown-channel-response
  "400 body for a ?channel param naming channels that aren't configured."
  [unknown]
  {:status 400
   :body {:error (str "unknown channel(s): " (str/join ", " unknown)
                      ". Check the configured channel keys.")}})

(defn- parse-channel-param [req]
  (get-in req [:parameters :query :channel]))

(defn daily-handler
  "POST /api/scheduling/daily — extend the playout horizon for every channel.
   Optional ?horizon=N (days, default 14). Optional ?channel=key to limit."
  [ctx]
  (fn [req]
    (try
      (let [channel-param (parse-channel-param req)]
        (if-let [unknown (unknown-channels ctx channel-param)]
          (unknown-channel-response unknown)
          (let [horizon (get-in req [:parameters :query :horizon] 14)
                ctx'    (filter-channels ctx channel-param)
                results (tasks/run-daily! ctx' :horizon horizon)]
            {:status 200 :body {:task "daily" :horizon horizon :results results}})))
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
      (let [channel-param (parse-channel-param req)]
        (if-let [unknown (unknown-channels ctx channel-param)]
          (unknown-channel-response unknown)
          (let [ctx' (filter-channels ctx channel-param)]
            {:status 200 :body {:task "weekly" :results (tasks/run-weekly! ctx')}})))
      (catch Exception e
        (log/error e "weekly scheduling task failed")
        {:status 500 :body {:error (util/error-message e)}}))))

(defn monthly-handler
  "POST /api/scheduling/monthly — propose + store sparse monthly overrides for
   every channel against their frozen grids. Returns 202 with a job ID.
   Optional ?channel=key to limit."
  [ctx]
  (fn [req]
    (let [channel-param (parse-channel-param req)]
      (if-let [unknown (unknown-channels ctx channel-param)]
        (unknown-channel-response unknown)
        (let [ctx' (filter-channels ctx channel-param)
              job  (jobs/submit-job!
                    (:job-runner ctx)
                    {:type :media/scheduling-monthly}
                    (fn [_report-progress]
                      (tasks/run-monthly! ctx')))]
          {:status 202 :body {:task "monthly" :job job}})))))

(defn quarterly-handler
  "POST /api/scheduling/quarterly — propose → check → repair → freeze the
   quarterly grid for every channel. Returns 202 with a job ID.
   Optional ?channel=key to limit."
  [ctx]
  (fn [req]
    (let [channel-param (parse-channel-param req)]
      (if-let [unknown (unknown-channels ctx channel-param)]
        (unknown-channel-response unknown)
        (let [ctx' (filter-channels ctx channel-param)
              job  (jobs/submit-job!
                    (:job-runner ctx)
                    {:type :media/scheduling-quarterly}
                    (fn [_report-progress]
                      (tasks/run-quarterly! ctx')))]
          {:status 202 :body {:task "quarterly" :job job}})))))
