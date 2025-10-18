(ns tunarr.scheduler.jobs.runner
  "In-memory asynchronous job runner for the Tunarr Scheduler service."
  (:require [taoensso.timbre :as log])
  (:import (java.time Instant)
           (java.util UUID)))

(defprotocol IJobRunner
  (jobs [self])
  (add-job! [self job-id job])
  (job-info [self job-id])
  (get-job [self job-id])
  (list-jobs [self])
  (submit! [self config job-fn])
  (close [self]))

(defn now []
  (Instant/now))

(defn format-ts [^Instant inst]
  (when inst
    (.toString inst)))

(defn- ->public-job
  [{:keys [id type status created-at started-at completed-at metadata result error progress]}]
  (cond-> {:id id
           :type type
           :status status
           :created-at (format-ts created-at)}
    metadata (assoc :metadata metadata)
    (some? progress) (assoc :progress progress)
    started-at (assoc :started-at (format-ts started-at))
    completed-at (assoc :completed-at (format-ts completed-at))
    (contains? #{:succeeded :failed} status) (assoc :result result)
    (= :failed status) (assoc :error error)))

(defn- sanitize-job-config
  [job-config]
  (cond
    (map? job-config) job-config
    (keyword? job-config) {:type job-config}
    :else (throw (ex-info "Job config must be a map or keyword"
                          {:job-config job-config}))))

(defn- require-job-type
  [{:keys [type] :as job-config}]
  (when-not type
    (throw (ex-info "Job type is required" {:job-config job-config})))
  job-config)

(defn- run-task
  "Execute the task function, supporting either zero-arity jobs or jobs that
  accept an update-progress callback."
  [task-fn update-progress]
  (try
    (task-fn update-progress)
    (catch clojure.lang.ArityException _
      (task-fn))))

(defmulti update-job! class)

#_(defn job-info
  "Fetch public information about a job."
  [runner job-id]
  (when-let [job (get @(.jobs runner) job-id)]
    (->public-job job)))

#_(defn list-jobs
  "Return public information about all known jobs ordered by creation time."
  [runner]
  (->> @(.jobs runner)
       vals
       (sort-by :created-at #(compare %2 %1))
       (map ->public-job)
       vec))

(defn submit-job!
  "Submit an asynchronous job. Returns the initial job information.

  The job-config map must include a :type keyword describing the job. Optional
  :metadata will be stored alongside the job record."
  [runner {:keys [type metadata]} task-fn]
  (let [job-id (str (UUID/randomUUID))
        update-progress (fn [progress]
                          (update-job! runner job-id merge {:progress progress}))]
    (add-job! runner job-id
              {:id job-id
               :type type
               :status :queued
               :metadata metadata
               :created-at (now)})
    (future
      (update-job! runner job-id merge {:status :running :started-at (now)})
      (try
        (let [result (run-task task-fn update-progress)]
          (update-job! runner job-id merge {:status :succeeded
                                            :completed-at (now)
                                            :result result}))
        (catch Throwable t
          (log/error t "Job failed" {:job-id job-id :type type})
          (update-job! runner job-id merge {:status :failed
                                            :completed-at (now)
                                            :error {:message (.getMessage t)
                                                    :type (.getName (class t))}}))))
    (job-info runner job-id)))

(defn shutdown!
  "Clear all tracked jobs."
  [runner]
  (close runner)
  nil)

(defrecord JobRunner [jobs]
  IJobRunner
  (jobs [_] @jobs)
  (add-job! [_ job-id job]
    (apply swap! jobs assoc job-id job))
  (get-job [_ job-id]
    (get @jobs job-id nil))
  (job-info [self job-id]
    (when-let [job  (get-job self job-id)]
      (->public-job job)))
  (list-jobs [_]
    (->> @jobs
         (vals)
         (sort-by :created-at #(compare %2 %1))
         (map ->public-job)
         (vec)))
  (submit! [self config task-fn]
    (submit-job! self (-> config
                          sanitize-job-config
                          require-job-type)
                 task-fn))
    
  java.io.Closeable
  (close [_] (reset! jobs {})))

(defn create
  "Create a new job runner instance."
  [_]
  (->JobRunner (atom {})))

(defmethod update-job! tunarr.scheduler.jobs.runner.JobRunner
  [runner id f & args]
  (apply swap! (:jobs runner) update id f args))
