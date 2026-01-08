(ns tunarr.scheduler.util.time
  "Time utility helpers for the scheduler."
  (:require [tick.core :as t]))

(defn daytime?
  "Return true if the instant falls within the configured daytime window.
   Note: start-hour must be less than end-hour. For overnight windows, call twice."
  [time-zone start-hour end-hour instant]
  {:pre [(< start-hour end-hour)]}
  (let [zoned (t/in instant (t/zone time-zone))
        hour (t/hour zoned)]
    (<= start-hour hour end-hour)))
