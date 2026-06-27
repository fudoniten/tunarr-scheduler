(ns tunarr.scheduler.http.api.media
  "HTTP handlers for media library operations."
  (:require [taoensso.timbre :as log]
            [clojure.walk :as walk]
            [tunarr.scheduler.jobs.runner :as jobs]
            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.media.sync :as media-sync]
            [tunarr.scheduler.media.pseudovision-sync :as pv-sync]
            [tunarr.scheduler.media.pseudovision-migration :as pv-migration]
            [tunarr.scheduler.media.pseudovision-media-sync :as pv-media-sync]
            [tunarr.scheduler.backends.pseudovision.client :as pv-client]
            [tunarr.scheduler.curation.core :as curate]
            [tunarr.scheduler.curation.tags :as curation-tags]
            [tunarr.scheduler.jobs.throttler :as throttler]
            [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.http.util :as util])
  (:import [java.time LocalDate Instant]))

;; ---------------------------------------------------------------------------
;; Helper functions
;; ---------------------------------------------------------------------------

(defn- serialize-time-fields
  "Recursively converts java.time.LocalDate and java.time.Instant values to
   ISO-8601 strings so Malli response coercion (which runs before Muuntaja JSON
   encoding) does not encounter raw Java time objects."
  [data]
  (walk/postwalk
   (fn [v]
     (cond
       (instance? LocalDate v) (str v)
       (instance? Instant v)   (str v)
       :else v))
   data))

(defn- resolve-media-by-id
  "Look up a media item by ID, accepting either the catalog's own ID or an
   external/Jellyfin ID.

   The catalog primary key (`media.id`) may hold either a Pseudovision
   media-item ID or its Jellyfin `remote-key`, depending on how the item was
   synced, and callers may legitimately send either form. We first try a
   direct catalog lookup. On a miss, and when Pseudovision is configured, we
   ask Pseudovision (which accepts internal or external IDs) to resolve the ID,
   then retry the catalog lookup against both the resolved item's internal
   `:id` and its Jellyfin `:remote-key` — whichever one was used as the
   catalog key will match."
  [catalog pseudovision media-id]
  (or (catalog/get-media-by-id catalog media-id)
      (when pseudovision
        (when-let [pv-item (try
                             (pv-client/get-media-item
                              (pv-client/get-config pseudovision) media-id)
                             (catch Exception e
                               (log/debug e "Pseudovision could not resolve media ID"
                                          {:media-id media-id})
                               nil))]
          (some (fn [candidate]
                  (when (not= candidate media-id)
                    (catalog/get-media-by-id catalog candidate)))
                (->> [(:id pv-item) (:remote-key pv-item)]
                     (remove nil?)
                     (map str)
                     distinct))))))

(defn- submit-job!
  "Generic job submission helper for async operations."
  [job-runner job-type library error-msg job-fn]
  (if-not library
    {:status 400 :body {:error error-msg}}
    (let [job (jobs/submit! job-runner
                            {:type job-type
                             :metadata {:library library}}
                            (fn [report-progress]
                              (job-fn {:library         library
                                       :report-progress report-progress})))]
      {:status 202 :body {:job job}})))

;; ---------------------------------------------------------------------------
;; Handlers
;; ---------------------------------------------------------------------------

(defn list-libraries-handler
  "List all media libraries from Pseudovision."
  [{:keys [pseudovision]}]
  (fn [_]
    (try
      (if-not pseudovision
        {:status 200 :body {:libraries []}}
        (let [pv-config (pv-client/get-config pseudovision)
              libraries (pv-client/list-all-libraries pv-config)]
          {:status 200 :body {:libraries libraries}}))
      (catch Exception e
        (log/error e "Error listing libraries")
        {:status 500 :body {:error (util/error-message e)}}))))

(defn sync-libraries-handler
  "Sync libraries from Pseudovision into the catalog."
  [{:keys [catalog pseudovision]}]
  (fn [_]
    (try
      (if-not pseudovision
        {:status 400 :body {:error "Pseudovision is not configured"}}
        (let [pv-config   (pv-client/get-config pseudovision)
              libraries   (pv-client/list-all-libraries pv-config)
              ;; Key by the library's name (matching the startup sync in
              ;; system.clj). The catalog stores `library.name` and looks
              ;; libraries up by name, and the upsert resolves conflicts on
              ;; `name` (refreshing the id). Keying by `:kind` instead stored
              ;; the kind as the name and caused `library_pkey` violations
              ;; whenever an already-synced id reappeared under a new name.
              library-map (into {} (map (fn [lib] [(keyword (:name lib)) (:id lib)]) libraries))]
          (catalog/update-libraries! catalog library-map)
          (log/info "Synced libraries from Pseudovision" {:count (count library-map)})
          {:status 200 :body {:libraries libraries}}))
      (catch Exception e
        (log/error e "Error syncing libraries from Pseudovision")
        {:status 500 :body {:error (util/error-message e)}}))))

(defn rescan-handler
  "Trigger async library rescan job."
  [{:keys [job-runner collection catalog]}]
  (fn [req]
    (try
      (let [library (get-in req [:parameters :path :library])]
        (submit-job! job-runner
                     :media/rescan
                     library
                     "library not specified for rescan"
                     (fn [opts] (media-sync/rescan-library! collection catalog opts))))
      (catch Exception e
        (log/error e "Error submitting rescan job")
        {:status 500 :body {:error (util/error-message e)}}))))

(defn retag-handler
  "Trigger async LLM retagging job.
   
   Supports optional ?kind=<type> parameter to filter by media kind (e.g., filler).
   When kind is provided, only media of that kind are retagged."
  [{:keys [job-runner catalog tunabrain throttler curation-config]}]
  (fn [req]
    (try
      (let [library (get-in req [:parameters :path :library])
            force   (= "true" (get-in req [:parameters :query :force]))
            kind    (get-in req [:parameters :query :kind])]
        (submit-job! job-runner
                     :media/retag
                     library
                     "library not specified for retag"
                     (fn [{:keys [report-progress]}]
                       (curate/retag-library!
                        (curate/->TunabrainCuratorBackend
                         tunabrain catalog throttler curation-config)
                        library
                        {:force force
                         :kind (when kind (keyword kind))
                         :report-progress report-progress}))))
      (catch Exception e
        (log/error e "Error submitting retag job")
        {:status 500 :body {:error (util/error-message e)}}))))

(defn tagline-handler
  "Generate taglines for library media with LLM."
  [{:keys [job-runner catalog tunabrain throttler curation-config]}]
  (fn [req]
    (try
      (let [library (get-in req [:parameters :path :library])]
        (submit-job! job-runner
                     :media/taglines
                     library
                     "library not specified for taglines"
                     (fn [_opts] (curate/generate-library-taglines!
                                 (curate/->TunabrainCuratorBackend
                                  tunabrain catalog throttler curation-config)
                                 library))))
      (catch Exception e
        (log/error e "Error submitting tagline job")
        {:status 500 :body {:error (util/error-message e)}}))))

(defn recategorize-handler
  "Recategorize library media with LLM."
  [{:keys [job-runner catalog tunabrain throttler curation-config]}]
  (fn [req]
    (try
      (let [library (get-in req [:parameters :path :library])
            force   (= "true" (get-in req [:parameters :query :force]))]
        (submit-job! job-runner
                     :media/recategorize
                     library
                     "library not specified for recategorize"
                     (fn [{:keys [report-progress]}]
                       (curate/recategorize-library!
                        (curate/->TunabrainCuratorBackend
                         tunabrain catalog throttler curation-config)
                        library
                        {:force force :report-progress report-progress}))))
      (catch Exception e
        (log/error e "Error submitting recategorize job")
        {:status 500 :body {:error (util/error-message e)}}))))

(defn retag-episodes-handler
  "Retag episode special flags with LLM."
  [{:keys [job-runner catalog tunabrain throttler curation-config]}]
  (fn [req]
    (try
      (let [library (get-in req [:parameters :path :library])
            force   (= "true" (get-in req [:parameters :query :force]))]
        (submit-job! job-runner
                     :media/retag-episodes
                     library
                     "library not specified for episode retagging"
                     (fn [{:keys [report-progress]}]
                       (curate/retag-library-episodes!
                        (curate/->TunabrainCuratorBackend
                         tunabrain catalog throttler curation-config)
                        library
                        {:force force :report-progress report-progress}))))
      (catch Exception e
        (log/error e "Error submitting episode retag job")
        {:status 500 :body {:error (util/error-message e)}}))))

(defn pseudovision-sync-handler
  "Sync library tags to Pseudovision (async job)."
  [{:keys [job-runner catalog pseudovision]}]
  (fn [req]
    (try
      (let [library (get-in req [:parameters :path :library])]
        (submit-job! job-runner
                     :media/pseudovision-sync
                     library
                     "library not specified for pseudovision sync"
                     (fn [{:keys [report-progress]}]
                       (pv-sync/sync-library-tags! catalog
                                                   (pv-client/get-config pseudovision)
                                                   library
                                                   {:report-progress report-progress}))))
      (catch Exception e
        (log/error e "Error submitting Pseudovision sync job")
        {:status 500 :body {:error (util/error-message e)}}))))

(defn migrate-to-pseudovision-handler
  "One-time migration from local catalog to Pseudovision."
  [{:keys [catalog pseudovision]}]
  (fn [req]
    (try
      (let [params              (get-in req [:parameters :body] {})
            dry-run?            (get params :dry-run false)
            include-categories? (get params :include-categories true)
            batch-size          (get params :batch-size 10)
            delay-ms            (get params :delay-ms 100)
            pv-config           (pv-client/get-config pseudovision)]

        (log/info "Starting Pseudovision migration"
                  {:dry-run dry-run?
                   :batch-size batch-size
                   :include-categories include-categories?})

        (let [result (pv-migration/migrate-all!
                      catalog
                      pv-config
                      {:dry-run dry-run?
                       :include-categories include-categories?
                       :batch-size batch-size
                       :delay-ms delay-ms})]

          {:status 200
           :body (assoc result :message
                        (if dry-run?
                          "Dry run complete - no changes made"
                          "Migration complete"))}))
      (catch Exception e
        (log/error e "Error during Pseudovision migration")
        {:status 500 :body {:error (util/error-message e)}}))))

(defn sync-from-pseudovision-handler
  "Sync media items from Pseudovision to catalog."
  [{:keys [catalog pseudovision]}]
  (fn [req]
    (try
      (let [library (get-in req [:parameters :path :library])]
        (when-not library
          (throw (ex-info "library parameter required" {:status 400})))

        (let [pv-config  (pv-client/get-config pseudovision)]

          (log/info "Syncing from Pseudovision" {:library library})

          (let [result (pv-media-sync/sync-library-from-pseudovision!
                        catalog
                        pv-config
                        library
                        {})]
            {:status 200 :body (assoc result :message "Pseudovision sync complete")})))
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          {:status (or (:status data) 500)
           :body {:error (util/error-message e)}}))
      (catch Exception e
        (log/error e "Error syncing from Pseudovision")
        {:status 500 :body {:error (util/error-message e)}}))))

(defn migrate-catalog-ids-handler
  "Migrate catalog IDs to use Pseudovision format."
  [{:keys [catalog pseudovision]}]
  (fn [req]
    (try
      (let [library (get-in req [:parameters :path :library])]
        (when-not library
          (throw (ex-info "library parameter required" {:status 400})))

        (let [pv-config  (pv-client/get-config pseudovision)
              library-kw (keyword library)]

          (log/info "Migrating catalog IDs to Pseudovision" {:library library})

          (let [result (pv-media-sync/migrate-catalog-to-pseudovision!
                        catalog
                        pv-config
                        library-kw)]
            {:status 200 :body (assoc result :message "Catalog ID migration complete")})))
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          {:status (or (:status data) 500)
           :body {:error (util/error-message e)}}))
      (catch Exception e
        (log/error e "Error migrating catalog IDs")
        {:status 500 :body {:error (util/error-message e)}}))))

(defn audit-tags-handler
  "Trigger async LLM tag audit job. Tags recommended for removal are deleted
   unless ?dry-run=true, in which case the recommendations are only reported
   in the job result."
  [{:keys [job-runner tunabrain catalog]}]
  (fn [req]
    (try
      (let [dry-run (= "true" (get-in req [:parameters :query :dry-run]))
            job (jobs/submit! job-runner
                              {:type :media/tag-audit
                               :metadata {:dry-run dry-run}}
                              (fn [_report-progress]
                                (curation-tags/audit-tags! catalog tunabrain
                                                           {:dry-run dry-run})))]
        {:status 202 :body {:job job}})
      (catch Exception e
        (log/error e "Error submitting tag audit job")
        {:status 500 :body {:error (util/error-message e)}}))))

(defn triage-tags-handler
  "Trigger async LLM tag governance triage job. Sends all tags with usage
   counts and example titles to tunabrain and applies its keep/remove/rename
   decisions, unless ?dry-run=true, in which case the decisions are only
   reported in the job result."
  [{:keys [job-runner tunabrain catalog]}]
  (fn [req]
    (try
      (let [dry-run      (= "true" (get-in req [:parameters :query :dry-run]))
            target-limit (get-in req [:parameters :query :target-limit])
            job (jobs/submit! job-runner
                              {:type :media/tag-triage
                               :metadata (cond-> {:dry-run dry-run}
                                           target-limit (assoc :target-limit target-limit))}
                              (fn [_report-progress]
                                (curation-tags/triage-tags! catalog tunabrain
                                                            {:dry-run dry-run
                                                             :target-limit target-limit})))]
        {:status 202 :body {:job job}})
      (catch Exception e
        (log/error e "Error submitting tag triage job")
        {:status 500 :body {:error (util/error-message e)}}))))

(defn get-library-media-handler
  "Get all media items in a library with process timestamps.
   
   Supports optional ?kind=<type> query parameter to filter by item_kind."
  [{:keys [catalog]}]
  (fn [req]
    (try
      (let [library (get-in req [:parameters :path :library])
            library-kw (keyword library)
            library-id (catalog/get-library-id catalog library-kw)
            kind-param (get-in req [:parameters :query :kind])
            kind (when kind-param (keyword kind-param))]
        (if library-id
          (let [media (if kind
                        (catalog/get-media-by-kind catalog library-kw kind)
                        (catalog/get-media-by-library-id catalog library-id))
                counts (when-not kind (catalog/count-media-by-kind catalog library-kw))]
            {:status 200
             :body (cond-> {:media (mapv serialize-time-fields media)}
                     counts (assoc :counts counts)
                     kind (assoc :kind kind))})
          {:status 404 :body {:error (str "Library not found: " library)}}))
      (catch Exception e
        (log/error e "Error fetching library media")
        {:status 500 :body {:error (util/error-message e)}}))))

(defn get-media-by-id-handler
  "Get a specific media item by ID with process timestamps.

   Accepts either the catalog's own media ID or an external/Jellyfin ID;
   external IDs are resolved through Pseudovision when configured."
  [{:keys [catalog pseudovision]}]
  (fn [req]
    (try
      (let [media-id (get-in req [:parameters :path :media-id])]
        (if-let [media (resolve-media-by-id catalog pseudovision media-id)]
          {:status 200 :body (serialize-time-fields media)}
          {:status 404 :body {:error (str "Media not found: " media-id)}}))
      (catch Exception e
        (log/error e "Error fetching media by ID")
        {:status 500 :body {:error (util/error-message e)}}))))

;; ---------------------------------------------------------------------------
;; Process timestamp reset
;; ---------------------------------------------------------------------------

(def ^:private valid-processes
  #{"retag" "recategorize" "episode-tagging"})

(defn- process-param->keyword
  "Convert a process query parameter to the internal keyword used in
   media_process_timestamp. Returns nil for unknown process names."
  [s]
  (case s
    "retag"          :process/tagging
    "recategorize"   :process/categorize
    "episode-tagging" :process/episode-tagging
    nil))

(defn delete-media-process-timestamp-handler
  "Delete the last-run timestamp for a specific process on a single media item,
   allowing the process to run again on next invocation."
  [{:keys [catalog pseudovision]}]
  (fn [req]
    (try
      (let [media-id (get-in req [:parameters :path :media-id])
            process  (get-in req [:parameters :path :process])]
        (if-let [process-kw (process-param->keyword process)]
          (if-let [media (resolve-media-by-id catalog pseudovision media-id)]
            (do (catalog/delete-process-timestamp! catalog (::media/id media) process-kw)
                {:status 200 :body {:media-id (::media/id media) :process process :reset true}})
            {:status 404 :body {:error (str "Media not found: " media-id)}})
          {:status 400 :body {:error (str "Unknown process: " process
                                          ". Valid processes: " (pr-str valid-processes))}}))
      (catch Exception e
        (log/error e "Error deleting process timestamp")
        {:status 500 :body {:error (util/error-message e)}}))))

(defn delete-library-process-timestamps-handler
  "Clear last-run timestamps for a single process across all media in a library."
  [{:keys [catalog]}]
  (fn [req]
    (try
      (let [library (get-in req [:parameters :path :library])
            process (get-in req [:parameters :path :process])]
        (if-let [process-kw (process-param->keyword process)]
          (do (catalog/delete-library-process-timestamps! catalog (keyword library) process-kw)
              {:status 200 :body {:library library :process process :reset true}})
          {:status 400 :body {:error (str "Unknown process: " process
                                          ". Valid processes: " (pr-str valid-processes))}}))
      (catch Exception e
        (log/error e "Error clearing library process timestamps")
        {:status 500 :body {:error (util/error-message e)}}))))

;; ---------------------------------------------------------------------------
;; Per-item curation actions
;; ---------------------------------------------------------------------------

(defn retag-media-item-handler
  "Retag a single media item via Tunabrain (async throttled)."
  [{:keys [catalog tunabrain throttler pseudovision]}]
  (fn [req]
    (try
      (let [media-id (get-in req [:parameters :path :media-id])]
        (if-let [media (resolve-media-by-id catalog pseudovision media-id)]
          (do
            (throttler/submit! throttler
                               curate/retag-media!
                               (curate/process-callback catalog media :process/tagging)
                               [tunabrain catalog media])
            {:status 202 :body {:media-id (::media/id media) :action "retag" :submitted true}})
          {:status 404 :body {:error (str "Media not found: " media-id)}}))
      (catch Exception e
        (log/error e "Error submitting per-item retag")
        {:status 500 :body {:error (util/error-message e)}}))))

(defn recategorize-media-item-handler
  "Recategorize a single media item via Tunabrain (async throttled)."
  [{:keys [catalog tunabrain throttler curation-config pseudovision]}]
  (fn [req]
    (try
      (let [media-id   (get-in req [:parameters :path :media-id])
            categories (get curation-config :categories)]
        (if-let [media (resolve-media-by-id catalog pseudovision media-id)]
          (do
            (throttler/submit! throttler
                               curate/recategorize-media!
                               (curate/process-callback catalog media :process/categorize)
                               [tunabrain catalog media categories])
            {:status 202 :body {:media-id (::media/id media) :action "recategorize" :submitted true}})
          {:status 404 :body {:error (str "Media not found: " media-id)}}))
      (catch Exception e
        (log/error e "Error submitting per-item recategorize")
        {:status 500 :body {:error (util/error-message e)}}))))

(defn sync-media-item-pseudovision-handler
  "Sync a single media item's tags to Pseudovision."
  [{:keys [catalog pseudovision]}]
  (fn [req]
    (try
      (if-not pseudovision
        {:status 400 :body {:error "Pseudovision is not configured"}}
        (let [media-id (get-in req [:parameters :path :media-id])]
          (if-let [media (resolve-media-by-id catalog pseudovision media-id)]
            (let [pv-config  (pv-client/get-config pseudovision)
                  catalog-id (::media/id media)
                  pv-item    (try (pv-client/get-media-item pv-config catalog-id)
                                  (catch Exception _ nil))
                  pv-item-id (or (:id pv-item) catalog-id)
                  result     (pv-sync/sync-item-tags! pv-config pv-item-id catalog media)]
              {:status 200 :body {:media-id catalog-id
                                  :action "sync-pseudovision"
                                  :synced (:synced result)
                                  :tags (:tags result)
                                  :error (:error result)}})
            {:status 404 :body {:error (str "Media not found: " media-id)}})))
      (catch Exception e
        (log/error e "Error syncing item to Pseudovision")
        {:status 500 :body {:error (util/error-message e)}}))))
