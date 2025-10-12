(ns tunarr.scheduler.scheduling.engine
  "Scheduling engine orchestrating channel programming."
  (:require [clojure.string :as str]
            [tick.core :as t]
            [taoensso.timbre :as log]
            [tunarr.scheduler.llm :as llm]
            [tunarr.scheduler.util.time :as time-util]))

(defrecord SchedulerEngine [config state]
  java.io.Closeable
  (close [_]
    (log/info "Closing scheduler engine")))

(defn create-engine [config]
  (log/info "Initialising scheduler engine" {:config config})
  (->SchedulerEngine config (atom {})))

(defn stop! [engine]
  (.close ^java.io.Closeable engine))

(defn seasonal-tags [engine date]
  (let [month-key (keyword (str/lower-case (t/format "MMMM" date)))]
    (get-in (:config engine) [:seasonal month-key] [])))

(defn daytime? [engine instant]
  (time-util/daytime? (:time-zone (:config engine))
                      (get-in (:config engine) [:daytime-hours :start])
                      (get-in (:config engine) [:daytime-hours :end])
                      instant))

(defn schedule-week!
  "Produce a weekly schedule for the supplied channel. Placeholder returns skeleton." [engine llm persistence {:keys [channel-id preferences]}]
  (log/info "Scheduling week" {:channel channel-id})
  (throw (ex-info "schedule-week! not implemented" {})))
