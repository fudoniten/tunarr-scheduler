(ns tunarr.scheduler.jobs.throttler-test
  (:require [clojure.test :refer [deftest is]]
            [tunarr.scheduler.jobs.throttler :as throttler]))

(def run-with-retries @#'throttler/run-with-retries)

(deftest run-with-retries-successful-execution
  (let [attempts (atom 0)
        result (run-with-retries (fn []
                                   (swap! attempts inc)
                                   :ok)
                                 {:retry-strategy (fn [_ _ _]
                                                    {:retry? false})})]
    (is (= 1 @attempts))
    (is (= {:ok :ok} result))))

(deftest run-with-retries-retries-then-succeeds
  (let [attempts (atom 0)
        strategy-calls (atom [])
        on-error-calls (atom [])
        result (run-with-retries
                 (fn []
                   (let [attempt (swap! attempts inc)]
                     (if (< attempt 3)
                       (throw (ex-info "boom" {:attempt attempt}))
                       :done)))
                 {:max-retries 5
                  :retry-strategy (fn [_ attempt _]
                                    (swap! strategy-calls conj attempt)
                                    {:retry? true :delay-ms 0})
                  :on-error (fn [_ info]
                              (swap! on-error-calls conj info))})]
    (is (= {:ok :done} result))
    (is (= 3 @attempts))
    (is (= [0 1] @strategy-calls))
    (is (= [{:attempt 0 :delay-ms 0}
            {:attempt 1 :delay-ms 0}]
           @on-error-calls))))

(deftest run-with-retries-gives-up-after-max-attempts
  (let [attempts (atom 0)
        on-error-calls (atom [])
        result (run-with-retries
                 (fn []
                   (swap! attempts inc)
                   (throw (RuntimeException. "fail")))
                 {:max-retries 2
                  :retry-strategy (fn [_ _ _]
                                    {:retry? true :delay-ms 0})
                  :on-error (fn [_ info]
                              (swap! on-error-calls conj info))})]
    (is (= 3 @attempts))
    (is (= 3 (count @on-error-calls)))
    (is (= [{:attempt 0 :delay-ms 0}
            {:attempt 1 :delay-ms 0}
            {:attempt 2 :final? true}]
           @on-error-calls))
    (is (contains? result :err))))

(deftest default-retry-strategy-non-retryable-status
  (let [ex (ex-info "bad request" {:status 400})]
    (is (= {:retry? false}
           (throttler/default-retry-strategy ex 0 {})))))

(deftest default-retry-strategy-uses-retry-after-header
  (let [ex (ex-info "too many" {:status 429 :headers {"Retry-After" "10"}})
        {:keys [retry? delay-ms]} (throttler/default-retry-strategy ex 1 {:base-backoff-ms 100})]
    (is retry?)
    (is (= 10000 delay-ms))))

(deftest default-retry-strategy-clamps-large-retry-after
  (let [ex (ex-info "service unavailable" {:status 503 :headers {"retry-after" "120"}})
        {:keys [retry? delay-ms]} (throttler/default-retry-strategy ex 0 {:base-backoff-ms 100})]
    (is retry?)
    (is (= 60000 delay-ms))))

(deftest throttler-enforces-minimum-interval
  (let [throttler (throttler/start-throttler 2 {:retry-strategy (fn [_ _ _]
                                                                  {:retry? false})})
        queue (:queue throttler)
        worker (:worker throttler)
        execution-times (atom [])]
    (try
      (let [promises (mapv (fn [_]
                             (let [p (promise)]
                               (.put queue {:f (fn []
                                                 (let [now (System/nanoTime)]
                                                   (swap! execution-times conj now))
                                                 :ok)
                                            :p p})
                               p))
                           (range 3))]
        (doseq [p promises]
          (is (= {:ok :ok} (deref p 2000 :timeout))))
        (is (= 3 (count @execution-times)))
        (let [intervals (map (fn [[a b]] (/ (- b a) 1e6))
                             (partition 2 1 @execution-times))]
          (doseq [interval intervals]
            (is (>= interval 400.0)))))
      (finally
        (.put queue ::stop)
        (.join ^Thread worker 2000))))
    (is (= 500000000 (get-in throttler [:opts :min-interval-ms]))))
