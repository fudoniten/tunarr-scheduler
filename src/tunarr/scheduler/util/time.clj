(ns tunarr.scheduler.util.time
  "Time utility helpers for the scheduler."
  (:require [tick.alpha.api :as t]))

(defn daytime?
  "Return true if the instant falls within the configured daytime window."
  [time-zone start-hour end-hour instant]
  (let [zoned (t/in instant (t/zone time-zone))
        hour (t/hour zoned)]
    (<= start-hour hour end-hour)))
