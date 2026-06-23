(ns tunarr.scheduler.cron
  "Self-contained cron scheduler for autonomous channel programming.

   Runs periodic scheduling tasks without requiring external triggers:
   • Daily  — extend playout horizon (ensure 14+ days scheduled)
   • Weekly — re-apply templates (catch template updates, seasonal changes)
   • Monthly — generate refined scheduling strategy via LLM
   • Quarterly — generate high-level programming outline via LLM

   Hermes (external) can still use the intent API for ad-hoc adjustments,
   but this component ensures the channel never runs out of content."
  (:require [taoensso.timbre :as log]
            [tunarr.scheduler.scheduling.templates :as templates]
            [tunarr.scheduler.scheduling.strategy :as strategy]
            [tunarr.scheduler.backends.pseudovision.client :as pv])
  (:import [java.time ZonedDateTime ZoneId]
           [java.util.concurrent Executors TimeUnit]
           [java.util.concurrent.atomic AtomicBoolean]))

;; ---------------------------------------------------------------------------
;; Task definitions
;; ---------------------------------------------------------------------------

(defn- daily-task
  "Extend the playout horizon for all channels.  Uses Pseudovision's
   rebuild-horizon endpoint so the cursor (and sequential episode positions)
   are preserved."
  [{:keys [pseudovision channels]}]
  (log/info "cron: running daily horizon extension")
  (doseq [[channel-key channel-cfg] channels]
    (let [channel-id (::channel-id channel-cfg)]
      (when channel-id
        (try
          (let [result (pv/rebuild-playout! pseudovision channel-id
                                            {:from "horizon" :horizon 14})]
            (log/info "cron: extended horizon" {:channel channel-key
                                                :channel-id channel-id
                                                :result result}))
          (catch Exception e
            (log/error e "cron: failed to extend horizon" {:channel channel-key
                                                           :channel-id channel-id})))))))

(defn- weekly-task
  "Re-apply schedule templates to all channels.  Picks up any template
   changes (e.g., seasonal slots) without resetting episode positions."
  [{:keys [pseudovision channels]}]
  (log/info "cron: running weekly template re-application")
  (try
    (let [results (templates/apply-templates-to-channels! pseudovision channels)]
      (log/info "cron: templates applied" {:results results}))
    (catch Exception e
      (log/error e "cron: failed to apply templates"))))

(defn- monthly-task
  "Generate a refined scheduling strategy via LLM.

   If auto-commit is enabled in the cron config, the strategy is applied
   immediately (the weekly template task will pick up the new strategy
   context).  Otherwise it is stored as a draft for review."
  [{:keys [llm channels executor auto-commit]}]
  (log/info "cron: running monthly strategy generation" {:auto-commit auto-commit})
  (when llm
    (let [s (strategy/generate-strategy! executor llm channels :monthly)]
      (if auto-commit
        (do
          (strategy/apply-strategy! executor (:id s))
          (log/info "cron: monthly strategy generated and committed"
                    {:id (:id s)}))
        (log/info "cron: monthly strategy generated (draft — awaiting review)"
                  {:id (:id s)})))))

(defn- quarterly-task
  "Generate a high-level programming outline via LLM.

   Same auto-commit behaviour as monthly-task."
  [{:keys [llm channels executor auto-commit]}]
  (log/info "cron: running quarterly strategy generation" {:auto-commit auto-commit})
  (when llm
    (let [s (strategy/generate-strategy! executor llm channels :quarterly)]
      (if auto-commit
        (do
          (strategy/apply-strategy! executor (:id s))
          (log/info "cron: quarterly strategy generated and committed"
                    {:id (:id s)}))
        (log/info "cron: quarterly strategy generated (draft — awaiting review)"
                  {:id (:id s)})))))

;; ---------------------------------------------------------------------------
;; Cron engine
;; ---------------------------------------------------------------------------

(defn- day-of-week
  "Returns 1-7 for Monday-Sunday of the given ZonedDateTime."
  [^ZonedDateTime zdt]
  (.getValue (.getDayOfWeek zdt)))

(defn- day-of-month
  "Returns 1-31."
  [^ZonedDateTime zdt]
  (.getDayOfMonth zdt))

(defn- month
  "Returns 1-12."
  [^ZonedDateTime zdt]
  (.getMonthValue zdt))

(defn- should-run-daily?
  "Daily tasks run every day at the configured hour."
  [^ZonedDateTime now {:keys [daily-hour]}]
  (= (.getHour now) (or daily-hour 3)))

(defn- should-run-weekly?
  "Weekly tasks run on the configured day at the configured hour."
  [^ZonedDateTime now {:keys [weekly-day weekly-hour]}]
  (and (= (day-of-week now) (or weekly-day 1))
       (= (.getHour now) (or weekly-hour 4))))

(defn- should-run-monthly?
  "Monthly tasks run on the configured day at the configured hour."
  [^ZonedDateTime now {:keys [monthly-day monthly-hour]}]
  (and (= (day-of-month now) (or monthly-day 1))
       (= (.getHour now) (or monthly-hour 5))))

(defn- should-run-quarterly?
  "Quarterly tasks run on the first day of Jan, Apr, Jul, Oct."
  [^ZonedDateTime now {:keys [quarterly-hour]}]
  (and (= (day-of-month now) 1)
       (#{1 4 7 10} (month now))
       (= (.getHour now) (or quarterly-hour 6))))

(defn- run-tasks
  "Evaluate which tasks should fire and execute them."
  [ctx now config]
  (let [ctx-with-commit (assoc ctx :auto-commit (boolean (:auto-commit config)))]
    (when (should-run-daily? now config)
      (daily-task ctx))
    (when (should-run-weekly? now config)
      (weekly-task ctx))
    (when (should-run-monthly? now config)
      (monthly-task ctx-with-commit))
    (when (should-run-quarterly? now config)
      (quarterly-task ctx-with-commit))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn create
  "Create a cron scheduler.  Returns a handle that can be passed to start!/stop!.

   Config keys:
     :enabled         — Boolean, default false
     :time-zone       — String timezone ID, default 'UTC'
     :check-interval  — Minutes between wake-ups, default 60
     :daily-hour      — Hour (0-23) for daily tasks, default 3
     :weekly-day      — Day (1-7, Mon-Sun) for weekly tasks, default 1 (Monday)
     :weekly-hour     — Hour for weekly tasks, default 4
     :monthly-day     — Day of month (1-31) for monthly tasks, default 1
     :monthly-hour    — Hour for monthly tasks, default 5
     :quarterly-hour  — Hour for quarterly tasks, default 6"
  [{:keys [enabled] :as config}]
  (if enabled
    (let [pool (Executors/newSingleThreadScheduledExecutor)
          running (AtomicBoolean. false)]
       {:pool pool
        :config (merge {:time-zone "UTC"
                        :check-interval 60
                        :daily-hour 3
                        :weekly-day 1
                        :weekly-hour 4
                        :monthly-day 1
                        :monthly-hour 5
                        :quarterly-hour 6
                        :auto-commit true}
                       config)
        :running running})
    (do (log/info "cron scheduler disabled")
        nil)))

(defn start!
  "Start the scheduler loop.  ctx is a map of deps (pseudovision, channels, llm)."
  [{:keys [pool config running] :as scheduler} ctx]
  (when (and scheduler pool)
    (let [zone (ZoneId/of (:time-zone config))
          interval-minutes (:check-interval config)
          task (fn []
                 (when (.get running)
                   (let [now (ZonedDateTime/now zone)]
                     (try
                       (run-tasks ctx now config)
                       (catch Exception e
                         (log/error e "cron: task execution failed"))))))]
      (.set running true)
      (.scheduleAtFixedRate pool
                            ^Runnable task
                            0
                            interval-minutes
                            TimeUnit/MINUTES)
      (log/info "cron scheduler started" {:interval-minutes interval-minutes
                                          :time-zone (:time-zone config)
                                          :daily-hour (:daily-hour config)
                                          :weekly-day (:weekly-day config)
                                          :weekly-hour (:weekly-hour config)
                                          :monthly-day (:monthly-day config)
                                          :monthly-hour (:monthly-hour config)
                                          :quarterly-hour (:quarterly-hour config)})
      scheduler)))

(defn stop!
  "Stop the scheduler and shut down its thread pool."
  [{:keys [pool running]}]
  (when pool
    (.set running false)
    (.shutdown pool)
    (try
      (.awaitTermination pool 5 TimeUnit/SECONDS)
      (catch Exception e
        (log/warn e "cron: pool did not terminate cleanly")))
    (log/info "cron scheduler stopped")))

(defn run-now
  "Manually trigger a full cycle of tasks (useful for testing)."
  [{:keys [config] :as scheduler} ctx]
  (when scheduler
    (let [zone (ZoneId/of (:time-zone config))
          now (ZonedDateTime/now zone)]
      (log/info "cron: manual trigger" {:now (str now)})
      (run-tasks ctx now config))))
