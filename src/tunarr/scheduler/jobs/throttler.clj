(ns tunarr.scheduler.jobs.throttler
  (:require [clj-http.client :as http]
            [taoensso.timbre :as log])
  (:import (java.util.concurrent LinkedBlockingQueue)
           (clojure.lang ExceptionInfo)))

(defrecord Throttler [rate queue worker stop? opts])

(defn- run-with-retries
  "run f with retry strategy. Returns {:ok value} or {:err throwable}."
  [f {:keys [max-retries retry-strategy on-error]
      :or   {max-retries 3}:as opts}]
  (loop [attempt 0]
    (let [result (try {:ok (f)}
                      (catch Throwable t {:exception t}))]
      (if-let [t (:exception result)]
        ;; we had an exception
        (if (>= attempt max-retries)
          (do (log/error (format "failed to perform request after %s attempts" attempt))
              (when on-error (on-error t {:attempt attempt :final? true}))
              {:err t})
          (let [{:keys [retry? delay-ms]} (retry-strategy t attempt opts)]
            (if-not retry?
              (do (log/error (format "failed to perform request after %s attempts" attempt))
                  (when on-error (on-error t {:attempt attempt :final? true}))
                  {:err t})
              (do (when on-error (on-error t {:attempt attempt :delay-ms delay-ms}))
                  (Thread/sleep (long delay-ms))
                  (recur (inc attempt))))))
        result))))

(defn default-retry-strategy
  [e attempt {:keys [base-backoff-ms] :or {base-backoff-ms 1000}}]
  (let [exd     (when (instance? ExceptionInfo e) (ex-data e))
        status  (:status exd)
        headers (:headers exd)
        retry-after (or (get headers "retry-after")
                        (get headers "Retry-After"))
        retry-after-ms (when retry-after
                         (try
                           (* 1000
                              (Long/parseLong
                               (re-find #"\d+" (str retry-after))))
                           (catch Exception _ nil)))
        retryable? (contains? #{429 500 502 503 504} status)]
    (if-not retryable?
      {:retry? false}
      (let [base    (* base-backoff-ms (Math/pow 2 attempt))
            jitter  (* 0.5 (rand))
            backoff (* base jitter)
            delay   (long (min 60000 (or retry-after-ms backoff)))]
        {:retry?   true
         :delay-ms delay}))))

(defn start-throttler
  ([rate] (start-throttler rate {}))
  ([rate {:keys [max-retries base-backoff-ms retry-strategy on-error]
          :or {max-retries     3
               base-backoff-ms 1000
               retry-strategy  default-retry-strategy}}]
   (let [queue           (LinkedBlockingQueue.)
         stop?           (atom false)
         min-interval-ms (long (/ 1e9 rate))
         opts            {:max-retries max-retries
                          :base-backoff-ms base-backoff-ms
                          :retry-strategy  retry-strategy
                          :on-error        on-error}
         worker          (doto
                             (Thread.
                              (fn []
                                (loop [last-ts 0]
                                  (when-not @stop?
                                    (let [task (.take queue)]
                                      (if (= ::stop task)
                                        (reset! stop? true)
                                        (let [now (System/nanoTime)
                                              last-ts (if (zero? last-ts)
                                                        (- now min-interval-ms)
                                                        last-ts)
                                              wait-ms (max 0 (- (+ last-ts min-interval-ms) now))]
                                          (when (pos? wait-ms)
                                            (Thread/sleep (long (/ wait-ms 1e6))))
                                          (let [{:keys [f p]} task]
                                            (deliver p (run-with-retries f opts))
                                            (recur (System/nanoTime))))))))))
                           (.setName (str "throttler-" (random-uuid)))
                           (.setDaemon true)
                           (.start))]
     (->Throttler rate queue worker stop?
                  (assoc opts :min-interval-ms min-interval-ms)))))
