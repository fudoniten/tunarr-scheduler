(ns tunarr.scheduler.jobs.throttler
  (:require [clojure.stacktrace :refer [print-stack-trace]]
            [clojure.core.async :refer [<!! >!! chan close!]]
            [taoensso.timbre :as log]))

(defn capture-stack-trace
  [e]
  (with-out-str (print-stack-trace e)))

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
