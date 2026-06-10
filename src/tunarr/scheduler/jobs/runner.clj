(ns tunarr.scheduler.jobs.runner
  "In-memory asynchronous job runner for the Tunarr Scheduler service."
  (:require [taoensso.timbre :as log]
            [clojure.spec.alpha :as s]
            [clojure.stacktrace :refer [print-stack-trace]])
  (:import (java.time Duration Instant)
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

(defn- duration-ms
  "Elapsed runtime: started-at to completed-at for finished jobs, started-at
   to now for jobs still running."
  [{:keys [started-at completed-at]}]
  (when started-at
    (.toMillis (Duration/between ^Instant started-at
                                 ^Instant (or completed-at (now))))))

(defn- ->public-job
  [job]
  (when job
    (let [{:keys [id type status created-at started-at completed-at metadata result error progress]}
          job]
      (cond-> {:id id
               :type type
               :status status
               :created-at (format-ts created-at)}
        metadata (assoc :metadata metadata)
        (some? progress) (assoc :progress progress)
        started-at (assoc :started-at (format-ts started-at)
                          :duration-ms (duration-ms job))
        completed-at (assoc :completed-at (format-ts completed-at))
        (contains? #{:succeeded :failed} status) (assoc :result result)
        (= :failed status) (assoc :error error)))))

(defmulti update-job! (fn [o & _] (class o)))

(def job-runner? (partial satisfies? IJobRunner))

(s/def ::type keyword?)

(s/def ::config
  (s/keys :req-un [::type]))

(s/def ::task-fn
  (s/fspec :args (s/cat :report-progress (s/fspec :args (s/cat :progress any?)))))

(defn- normalize-config
  "Accept either a bare keyword job type or a map with a :type keyword."
  [config]
  (let [config (if (keyword? config) {:type config} config)]
    (when-not (and (map? config) (keyword? (:type config)))
      (throw (ex-info "Job config must be a map or keyword with a keyword :type"
                      {:config config})))
    config))

;; ---------------------------------------------------------------------------
;; Standard progress shape
;;
;; Jobs that process a known set of items report :progress as a map:
;;
;;   {:phase "tagging" :total 340 :skipped 88 :completed 12 :failed 1
;;    :current-item {:id "..." :name "..."}}
;;
;; The report-progress callback handed to each task accepts either a map
;; (which replaces the job's :progress wholesale) or a function of the
;; current progress map (applied atomically, for concurrent counter
;; updates). The helpers below produce atomic updates against the standard
;; shape.
;; ---------------------------------------------------------------------------

(defn start-items!
  "Initialize item-based progress tracking for a job phase."
  [report-progress phase total skipped]
  (report-progress {:phase phase :total total :skipped skipped :completed 0 :failed 0}))

(defn item-started!
  "Record the item that is currently being processed."
  [report-progress {:keys [id name]}]
  (report-progress #(assoc % :current-item {:id id :name name})))

(defn item-completed!
  "Atomically count one item as completed."
  [report-progress]
  (report-progress #(-> % (update :completed (fnil inc 0)) (dissoc :current-item))))

(defn item-failed!
  "Atomically count one item as failed."
  [report-progress]
  (report-progress #(-> % (update :failed (fnil inc 0)) (dissoc :current-item))))

(defn submit-job!
  "Submit an asynchronous job. Returns the initial job information.

  The job config may be a bare keyword job type or a map with a :type
  keyword. Optional :metadata will be stored alongside the job record.

  task-fn is called with a report-progress function; see the progress shape
  notes above for its semantics."
  [runner config task-fn]
  (let [{:keys [type metadata]} (normalize-config config)
        job-id (str (UUID/randomUUID))
        new-job {:id job-id
                 :type type
                 :status :queued
                 :metadata metadata
                 :created-at (now)}
        update-progress (fn [progress]
                          (update-job! runner job-id
                                       (fn [job]
                                         (assoc job :progress
                                                (if (fn? progress)
                                                  (progress (or (:progress job) {}))
                                                  progress)))))]
    (log/info (format "creating job: %s" job-id))
    (add-job! runner job-id new-job)
    (future
      (try
        (update-job! runner job-id merge {:status :running :started-at (now)})
        (log/info (format "job running: %s" job-id))
        (let [result (task-fn update-progress)]
          (update-job! runner job-id merge {:status :succeeded
                                            :completed-at (now)
                                            :result result}))
        (catch Throwable t
          (log/error t "Job failed" {:job-id job-id :type type})
          (log/info (with-out-str (print-stack-trace t)))
          (update-job! runner job-id merge {:status :failed
                                            :completed-at (now)
                                            :error {:message (.getMessage t)
                                                    :type (.getName (class t))}}))))
    (->public-job new-job)))

(s/def ::job-runner job-runner?)
(s/fdef submit-job!
  :args (s/cat :runner  ::job-runner
               :config  ::config
               :task-fn ::task-fn))

(defn shutdown!
  "Clear all tracked jobs."
  [runner]
  (close runner)
  nil)

(defrecord JobRunner [jobs]
  IJobRunner
  (jobs [_] @jobs)
  (add-job! [_ job-id job]
    (log/info (format "adding job %s with config %s" job-id job))
    (swap! jobs (fn [m]
                  (let [m' (assoc m job-id job)]
                    ;; Keep only the last 100 jobs to prevent unbounded growth
                    (if (> (count m') 100)
                      (into {} (take-last 100 (sort-by (comp :created-at val) m')))
                      m')))))
  (get-job [_ job-id]
    (get @jobs job-id nil))
  (job-info [self job-id]
    (when-let [job (get-job self job-id)]
      (->public-job job)))
  (list-jobs [_]
    (->> @jobs
         (vals)
         (sort-by :created-at #(compare %2 %1))
         (map ->public-job)
         (vec)))
  (submit! [self config task-fn]
    (submit-job! self config task-fn))
  (close [_] (reset! jobs {})))

(defn create
  "Create a new job runner instance."
  [_]
  (->JobRunner (atom {})))

(defmethod update-job! JobRunner
  [runner id f & args]
  (swap! (:jobs runner) update id
         (fn [job] (apply f (cons job args)))))
