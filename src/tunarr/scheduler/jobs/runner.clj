(ns tunarr.scheduler.jobs.runner
  "In-memory asynchronous job runner for the Tunarr Scheduler service."
  (:require [taoensso.timbre :as log])
  (:import (java.time Instant)
           (java.util UUID)))

(defrecord JobRunner [jobs]
  java.io.Closeable
  (close [_]
    (reset! jobs {})))

(defn create
  "Create a new job runner instance."
  [_]
  (->JobRunner (atom {})))

(defn- now []
  (Instant/now))

(defn- format-ts [^Instant inst]
  (when inst
    (.toString inst)))

(defn- ->public-job
  [{:keys [id type status created-at started-at completed-at metadata result error]}]
  (cond-> {:id id
           :type type
           :status status
           :created-at (format-ts created-at)}
    metadata (assoc :metadata metadata)
    started-at (assoc :started-at (format-ts started-at))
    completed-at (assoc :completed-at (format-ts completed-at))
    (contains? #{:succeeded :failed} status) (assoc :result result)
    (= :failed status) (assoc :error error)))

(defn- update-job! [jobs id f & args]
  (apply swap! jobs update id f args))

(defn job-info
  "Fetch public information about a job."
  [runner job-id]
  (when-let [job (get @(.jobs runner) job-id)]
    (->public-job job)))

(defn list-jobs
  "Return public information about all known jobs ordered by creation time."
  [runner]
  (->> @(.jobs runner)
       vals
       (sort-by :created-at #(compare %2 %1))
       (map ->public-job)
       vec))

(defn submit!
  "Submit an asynchronous job. Returns the initial job information.

  The job-config map must include a :type keyword describing the job. Optional
  :metadata will be stored alongside the job record."
  ([runner job-type task-fn]
   (submit! runner {:type job-type} task-fn))
  ([runner {:keys [type metadata] :as job-config} task-fn]
   (when-not type
     (throw (ex-info "Job type is required" {:job-config job-config})))
   (let [job-id (str (UUID/randomUUID))
         created-at (now)
         job {:id job-id
              :type type
              :status :queued
              :metadata metadata
              :created-at created-at}]
     (swap! (.jobs runner) assoc job-id job)
     (future
       (let [start (now)]
         (update-job! (.jobs runner) job-id merge {:status :running
                                                   :started-at start})
         (try
           (let [result (task-fn)
                 finished (now)]
             (update-job! (.jobs runner) job-id merge {:status :succeeded
                                                       :completed-at finished
                                                       :result result}))
           (catch Throwable t
             (log/error t "Job failed" {:job-id job-id :type type})
             (let [finished (now)]
               (update-job! (.jobs runner) job-id merge {:status :failed
                                                         :completed-at finished
                                                         :error {:message (.getMessage t)
                                                                 :type (.getName (class t))}}))))))
     (job-info runner job-id))))

(defn shutdown!
  "Clear all tracked jobs."
  [runner]
  (.close runner)
  nil)
