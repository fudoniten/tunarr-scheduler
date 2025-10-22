(ns tunarr.scheduler.media.sync
  "Utilities for synchronising media collections into the catalog."
  (:require [taoensso.timbre :as log]
            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.media.collection :as collection]))

(defn- normalize-library [library]
  (cond
    (keyword? library) library
    (string? library) (keyword library)
    :else (throw (ex-info "Unsupported library identifier"
                          {:library library}))))

(defn rescan-library!
  "Pull media from the configured collection for the provided libraries and store
  them in the catalog.

  Returns a map detailing how many items were imported per library." 
  [collection catalog {:keys [library report-progress]}]
  (let [library (normalize-library library)]
    (log/info "Starting media rescan" {:library library})
    (let [result
          (let [items (map-indexed vector (collection/get-library-items collection library))
                total-items (count items)]
            (doseq [[n item] items]
              (log/info (format "adding media item: %s" (::media/name item)))
              (catalog/add-media catalog item)
              (report-progress {:library        library
                                :total-items    total-items
                                :complete-items n}))
            {:library library
             :count   total-items})]
      (log/info "Completed media rescan" {:libraries library
                                          :results   result})
      {:library library :result result})))
