(ns tunarr.scheduler.jobs.throttler
  (:require [clojure.core.async :refer [<!! >!! chan close!]]
            [clojure.pprint :refer [pprint]]
            [taoensso.timbre :as log]
            [tunarr.scheduler.util.error :refer [capture-stack-trace]])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(defprotocol IThrottler
  (submit!
    [self f]
    [self f callback]
    [self f callback args])
  (start! [self])
  (stop! [self]))

(defrecord Throttler [rate runner running? jobs]
  IThrottler
  (submit! [self f] (submit! self f nil []))
  (submit! [self f callback] (submit! self f callback []))
  (submit! [_ f callback args]
    (if-not @running?
      (throw (ex-info "Submitted job to stopped throttler" {}))
      (>!! jobs {:job f :callback callback :args args})))
  (start! [_]
    (reset! runner
            (future
              (compare-and-set! running? false true)
              (let [delay-ms (long (/ 1000.0 rate))]
                (loop [next-run (+ (System/currentTimeMillis) delay-ms)]
                  (when @running?
                    (if-let [{:keys [job args callback]} (<!! jobs)]
                      (do (try
                            (let [result (apply job args)]
                              (when callback
                                (try (callback {:result result})
                                     (catch Throwable t
                                       (log/error (format "Callback threw: %s" t))
                                       (log/debug (capture-stack-trace t))))))
                            (catch Throwable t
                              (log/error (format "Job threw: %s" t))
                              (when callback (callback {:error t}))))
                          (let [delay (- next-run (System/currentTimeMillis))]
                            (when (pos? delay)
                              (Thread/sleep delay)))
                          (recur (+ (System/currentTimeMillis) delay-ms)))
                      (do (reset! running? false)
                          (close! jobs)))))))))
  (stop! [_]
    (reset! running? false)))

(defn create [& {:keys [rate queue-size]
                 :or {rate       2
                      queue-size 1024}}]
  (->Throttler rate (atom nil) (atom false) (chan queue-size)))

;; ---------------------------------------------------------------------------
;; Retry Logic
;; ---------------------------------------------------------------------------

(defn default-retry-strategy
  "Default retry strategy for HTTP requests.
   
   Non-retryable status codes: 400-499 (client errors)
   Retryable status codes: 429 (rate limit), 500-599 (server errors)
   
   Uses Retry-After header if present, otherwise exponential backoff.
   Clamps retry delay to 60 seconds max."
  [ex attempt opts]
  (let [data (ex-data ex)
        status (:status data)
        headers (:headers data)
        retry-after (or (get headers "Retry-After")
                       (get headers "retry-after"))
        base-backoff-ms (:base-backoff-ms opts 1000)]
    
    (cond
      ;; Rate limits and server errors are retryable
      (or (= status 429)
          (and status (>= status 500)))
      (if retry-after
        (let [delay-ms (* (Integer/parseInt retry-after) 1000)
              clamped-delay (min delay-ms 60000)]
          {:retry? true :delay-ms clamped-delay})
        (let [backoff-ms (* base-backoff-ms (Math/pow 2 attempt))
              clamped-delay (min backoff-ms 60000)]
          {:retry? true :delay-ms (long clamped-delay)}))
      
      ;; Client errors are not retryable
      (and status (>= status 400) (< status 500))
      {:retry? false}
      
      ;; Default: retry with exponential backoff
      :else
      (let [backoff-ms (* base-backoff-ms (Math/pow 2 attempt))
            clamped-delay (min backoff-ms 60000)]
        {:retry? true :delay-ms (long clamped-delay)}))))

(defn- run-with-retries
  "Runs a function with retry logic.
   
   Options:
   - :max-retries - Maximum number of retry attempts (default: 3)
   - :retry-strategy - Function (ex, attempt, opts) -> {:retry? bool :delay-ms num}
   - :on-error - Callback function (ex, info) called on each error
   
   Returns {:ok result} on success or {:err exception} on final failure."
  [f opts]
  (let [max-retries (:max-retries opts 3)
        retry-strategy (:retry-strategy opts default-retry-strategy)
        on-error (:on-error opts)]
    (loop [attempt 0]
      (let [result (try
                     {:ok (f)}
                     (catch Throwable ex
                       {:err ex}))]
        (if (:ok result)
          result
          (let [ex (:err result)]
            (if (>= attempt max-retries)
              ;; Max retries exceeded
              (do
                (when on-error
                  (on-error ex {:attempt attempt :final? true}))
                {:err ex})
              ;; Check retry strategy
              (let [{:keys [retry? delay-ms]} (retry-strategy ex attempt opts)]
                (when on-error
                  (on-error ex (if retry?
                                {:attempt attempt :delay-ms delay-ms}
                                {:attempt attempt :final? true})))
                (if retry?
                  (do
                    (when (and delay-ms (pos? delay-ms))
                      (Thread/sleep delay-ms))
                    (recur (inc attempt)))
                  {:err ex})))))))))

;; ---------------------------------------------------------------------------
;; New Throttler Implementation (for tests)
;; ---------------------------------------------------------------------------

(defn start-throttler
  "Starts a new throttler with minimum interval enforcement.
   
   rate-per-second - Maximum requests per second (e.g., 2 for 500ms minimum interval)
   opts - Options map:
     :retry-strategy - Retry strategy function
     :base-backoff-ms - Base backoff for exponential retry (default: 1000ms)
     :max-retries - Maximum retry attempts (default: 3)
     :min-interval-ms - Minimum interval between requests in ms (calculated from rate)
   
   Returns throttler map with :queue, :worker, and :opts"
  [rate-per-second opts]
  (let [min-interval-ms (long (/ 1000.0 rate-per-second))
        queue (LinkedBlockingQueue.)
        last-execution (atom 0)
        running (atom true)
        ;; Daemon: the worker polls forever until it receives ::stop, and
        ;; nothing in the codebase stops a throttler (no integrant halt hook),
        ;; so a non-daemon thread would pin the JVM open forever — which hung
        ;; the flake's test build. A background poller must not block exit.
        worker (doto (Thread.
                      (fn []
                        (while @running
                          (try
                            (when-let [job (.poll queue 100 TimeUnit/MILLISECONDS)]
                              (if (= job ::stop)
                                (reset! running false)
                                (let [{:keys [f p]} job
                                      now (System/nanoTime)
                                      elapsed-ns (- now @last-execution)
                                      elapsed-ms (/ elapsed-ns 1e6)
                                      sleep-ms (max 0 (- min-interval-ms elapsed-ms))]
                                  (when (pos? sleep-ms)
                                    (Thread/sleep (long sleep-ms)))
                                  (let [result (run-with-retries f opts)]
                                    (reset! last-execution (System/nanoTime))
                                    (when p
                                      (deliver p result))))))
                            (catch InterruptedException _
                              (reset! running false))
                            (catch Exception e
                              (log/error e "Error in throttler worker"))))))
                 (.setDaemon true))]
    (.start worker)
    {:queue queue
     :worker worker
     :opts (assoc opts :min-interval-ms (long (* min-interval-ms 1e6)))}))
