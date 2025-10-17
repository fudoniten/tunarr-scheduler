(ns tunarr.scheduler.media.sync
  "Utilities for synchronising media collections into the catalog."
  (:require [taoensso.timbre :as log]
            [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.media.collection :as collection]))

(defn- normalize-library [library]
  (cond
    (keyword? library) library
    (string? library) (keyword library)
    :else (throw (ex-info "Unsupported library identifier"
                          {:library library}))))

(defn rescan-libraries!
  "Pull media from the configured collection for the provided libraries and store
  them in the catalog.

  Returns a map detailing how many items were imported per library." 
  [collection catalog {:keys [libraries]}]
  (when (empty? libraries)
    (throw (ex-info "No libraries provided" {})))
  (let [libraries (map normalize-library libraries)]
    (log/info "Starting media rescan" {:libraries libraries})
    (let [results
          (for [library libraries]
            (let [items (vec (collection/get-library-items collection library))]
              (doseq [item items]
                (catalog/add-media catalog item))
              {:library (name library)
               :count (count items)}))]
      (log/info "Completed media rescan" {:libraries libraries
                                           :results results})
      {:libraries results
       :total (reduce + 0 (map :count results))})))
