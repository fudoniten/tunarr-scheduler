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
        (let [job (runner/submit! job-runner {:type :test/success} (fn [_] :done))
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
        (let [job (runner/submit! job-runner {:type :test/failure}
                                  (fn [_] (throw (RuntimeException. "boom"))))
              final (await-final-status job-runner (:id job))]
          (is final)
          (is (= :failed (:status final)))
          (is (= "boom" (get-in final [:error :message])))
          (is (= "java.lang.RuntimeException" (get-in final [:error :type])))
          (is (nil? (get-in final [:error :error])))))
      (finally
        (runner/shutdown! job-runner)))))

(deftest list-jobs-orders-by-creation-test
  (let [job-runner (runner/create {})]
    (try
      (testing "jobs are returned with newest first"
        (let [first-job (runner/submit! job-runner {:type :test/one} (fn [_] (Thread/sleep 5) :one))
              second-job (runner/submit! job-runner {:type :test/two} (fn [_] :two))]
          (await-final-status job-runner (:id first-job))
          (await-final-status job-runner (:id second-job))
          (let [jobs (runner/list-jobs job-runner)]
            (is (= 2 (count jobs)))
            (is (= (:id second-job) (:id (first jobs)))))))
      (finally
        (runner/shutdown! job-runner)))))

(deftest submit-job-normalizes-keyword-config
  (let [job-runner (runner/create {})]
    (try
      (let [job (runner/submit! job-runner :test/keyword (fn [_] :ok))
            final (await-final-status job-runner (:id job))]
        (is (= :succeeded (:status final)))
        (is (= :test/keyword (:type final))))
      (finally
        (runner/shutdown! job-runner)))))

(deftest submit-job-progress-exposed
  (let [job-runner (runner/create {})
        latch (promise)]
    (try
      (let [job (runner/submit! job-runner
                                {:type :test/progress}
                                (fn [update-progress]
                                  (update-progress 0.42)
                                  @latch
                                  :done))]
        (let [job-id (:id job)]
          (loop [remaining 200]
            (let [info (runner/job-info job-runner job-id)]
              (when (and (pos? remaining)
                         (not= 0.42 (:progress info)))
                (Thread/sleep 10)
                (recur (dec remaining)))))
          (is (= 0.42 (:progress (runner/job-info job-runner job-id))))
          (deliver latch true)
          (let [final (await-final-status job-runner job-id)]
            (is (= :succeeded (:status final)))
            (is (= :done (:result final))))))
      (finally
        (runner/shutdown! job-runner)))))

(deftest submit-job-invalid-config-throws
  (let [job-runner (runner/create {})]
    (try
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Job config must be a map or keyword"
           (runner/submit! job-runner 123 (fn [_] :nope))))
      (finally
        (runner/shutdown! job-runner)))))

(deftest submit-job-fn-progress-applies-atomically
  (let [job-runner (runner/create {})]
    (try
      (testing "functional progress updates transform the current progress map"
        (let [job (runner/submit! job-runner
                                  {:type :test/fn-progress}
                                  (fn [report-progress]
                                    (runner/start-items! report-progress "tagging" 3 1)
                                    (runner/item-started! report-progress {:id "m1" :name "Cowboy Bebop"})
                                    (runner/item-completed! report-progress)
                                    (runner/item-started! report-progress {:id "m2" :name "Trigun"})
                                    (runner/item-failed! report-progress)
                                    (runner/item-started! report-progress {:id "m3" :name "Akira"})
                                    (runner/item-completed! report-progress)
                                    :done))
              final (await-final-status job-runner (:id job))]
          (is (= :succeeded (:status final)))
          (is (= {:phase "tagging" :total 3 :skipped 1 :completed 2 :failed 1}
                 (:progress final)))))
      (finally
        (runner/shutdown! job-runner)))))

(deftest job-reports-current-item-and-duration
  (let [job-runner (runner/create {})
        started (promise)
        release (promise)]
    (try
      (let [job (runner/submit! job-runner
                                {:type :test/current-item}
                                (fn [report-progress]
                                  (runner/start-items! report-progress "tagging" 1 0)
                                  (runner/item-started! report-progress {:id "m1" :name "Cowboy Bebop"})
                                  (deliver started true)
                                  @release
                                  (runner/item-completed! report-progress)
                                  :done))
            job-id (:id job)]
        @started
        (loop [remaining 200]
          (let [info (runner/job-info job-runner job-id)]
            (when (and (pos? remaining)
                       (not= "Cowboy Bebop" (get-in info [:progress :current-item :name])))
              (Thread/sleep 10)
              (recur (dec remaining)))))
        (let [info (runner/job-info job-runner job-id)]
          (is (= {:id "m1" :name "Cowboy Bebop"}
                 (get-in info [:progress :current-item])))
          (is (int? (:duration-ms info)))
          (is (>= (:duration-ms info) 0)))
        (deliver release true)
        (let [final (await-final-status job-runner job-id)]
          (is (= :succeeded (:status final)))
          (is (nil? (get-in final [:progress :current-item])))
          (is (int? (:duration-ms final)))))
      (finally
        (runner/shutdown! job-runner)))))
