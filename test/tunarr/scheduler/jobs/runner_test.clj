(ns tunarr.scheduler.jobs.runner-test
  (:require [clojure.test :refer [deftest is testing]]
            [tunarr.scheduler.jobs.runner :as runner]))

(defn- await-final-status [job-runner job-id]
  (loop [remaining 200]
    (let [info (runner/job-info job-runner job-id)]
      (cond
        (nil? info) nil
        (#{:succeeded :failed} (:status info)) info
        (pos? remaining) (do (Thread/sleep 10)
                             (recur (dec remaining)))
        :else info))))

(deftest submit-job-success-test
  (let [job-runner (runner/create {})]
    (try
      (testing "job completes successfully"
        (let [job (runner/submit! job-runner :test/success (fn [] :done))
              final (await-final-status job-runner (:id job))]
          (is final)
          (is (= :succeeded (:status final)))
          (is (= :done (:result final)))))
      (finally
        (runner/shutdown! job-runner)))))

(deftest submit-job-failure-test
  (let [job-runner (runner/create {})]
    (try
      (testing "job failure captures error information"
        (let [job (runner/submit! job-runner :test/failure (fn [] (throw (RuntimeException. "boom"))))
              final (await-final-status job-runner (:id job))]
          (is final)
          (is (= :failed (:status final)))
          (is (= "boom" (get-in final [:error :message])))))
      (finally
        (runner/shutdown! job-runner)))))

(deftest list-jobs-orders-by-creation-test
  (let [job-runner (runner/create {})]
    (try
      (testing "jobs are returned with newest first"
        (let [first-job (runner/submit! job-runner :test/one (fn [] (Thread/sleep 5) :one))
              second-job (runner/submit! job-runner :test/two (fn [] :two))]
          (await-final-status job-runner (:id first-job))
          (await-final-status job-runner (:id second-job))
          (let [jobs (runner/list-jobs job-runner)]
            (is (= 2 (count jobs)))
            (is (= (:id second-job) (:id (first jobs)))))))
      (finally
        (runner/shutdown! job-runner)))))
