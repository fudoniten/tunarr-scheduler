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
  [collection catalog {:keys [library report-progress batch-size]
                       :or {batch-size 250}}]
  (let [library (normalize-library library)]
    (log/info "Starting media rescan" {:library library :batch-size batch-size})
    (let [items (collection/get-library-items collection library)
          processed-count (volatile! 0)
          result
          (do
            ;; Process items in batches to reduce database transactions
            (doseq [batch (partition-all batch-size items)]
              (log/info (format "Processing batch of %d items (total processed: %d)"
                                (count batch)
                                @processed-count))
              (catalog/add-media-batch! catalog batch)
              (vswap! processed-count + (count batch))
              (report-progress {:library        library
                                :complete-items @processed-count}))
            {:library library
             :count   @processed-count})]
      (log/info "Completed media rescan" {:library library
                                          :results result})
      {:library library :result result})))
