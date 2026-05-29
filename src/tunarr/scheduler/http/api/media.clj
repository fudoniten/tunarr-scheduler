(ns tunarr.scheduler.http.api.media
  "HTTP handlers for media library operations."
  (:require [taoensso.timbre :as log]
            [clojure.walk :as walk]
            [tunarr.scheduler.jobs.runner :as jobs]
            [tunarr.scheduler.media.sync :as media-sync]
            [tunarr.scheduler.media.pseudovision-sync :as pv-sync]
            [tunarr.scheduler.media.pseudovision-migration :as pv-migration]
            [tunarr.scheduler.media.pseudovision-media-sync :as pv-media-sync]
            [tunarr.scheduler.backends.pseudovision.client :as pv-client]
            [tunarr.scheduler.curation.core :as curate]
            [tunarr.scheduler.tunabrain :as tunabrain]
            [tunarr.scheduler.media.catalog :as catalog])
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
        {:status 500 :body {:error (.getMessage e)}}))))

(defn sync-libraries-handler
  "Sync libraries from Pseudovision into the catalog."
  [{:keys [catalog pseudovision]}]
  (fn [_]
    (try
      (if-not pseudovision
        {:status 400 :body {:error "Pseudovision is not configured"}}
        (let [pv-config   (pv-client/get-config pseudovision)
              libraries   (pv-client/list-all-libraries pv-config)
              library-map (into {} (map (fn [lib] [(keyword (:kind lib)) (:id lib)]) libraries))]
          (catalog/update-libraries! catalog library-map)
          (log/info "Synced libraries from Pseudovision" {:count (count library-map)})
          {:status 200 :body {:libraries libraries}}))
      (catch Exception e
        (log/error e "Error syncing libraries from Pseudovision")
        {:status 500 :body {:error (.getMessage e)}}))))

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
        {:status 500 :body {:error (.getMessage e)}}))))

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
                     (fn [opts] (curate/retag-library!
                                 (curate/->TunabrainCuratorBackend
                                  tunabrain catalog throttler curation-config)
                                 library
                                 {:force force :kind (when kind (keyword kind))}))))
      (catch Exception e
        (log/error e "Error submitting retag job")
        {:status 500 :body {:error (.getMessage e)}}))))

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
                     (fn [opts] (curate/generate-library-taglines!
                                 (curate/->TunabrainCuratorBackend
                                  tunabrain catalog throttler curation-config)
                                 library))))
      (catch Exception e
        (log/error e "Error submitting tagline job")
        {:status 500 :body {:error (.getMessage e)}}))))

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
                     (fn [opts] (curate/recategorize-library!
                                 (curate/->TunabrainCuratorBackend
                                  tunabrain catalog throttler curation-config)
                                 library
                                 {:force force}))))
      (catch Exception e
        (log/error e "Error submitting recategorize job")
        {:status 500 :body {:error (.getMessage e)}}))))

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
                     (fn [_opts] (curate/retag-library-episodes!
                                  (curate/->TunabrainCuratorBackend
                                   tunabrain catalog throttler curation-config)
                                  library
                                  {:force force}))))
      (catch Exception e
        (log/error e "Error submitting episode retag job")
        {:status 500 :body {:error (.getMessage e)}}))))

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
                     (fn [opts] (pv-sync/sync-library-tags! catalog
                                                             pseudovision
                                                             library))))
      (catch Exception e
        (log/error e "Error submitting Pseudovision sync job")
        {:status 500 :body {:error (.getMessage e)}}))))

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
        {:status 500 :body {:error (.getMessage e)}}))))

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
           :body {:error (.getMessage e)}}))
      (catch Exception e
        (log/error e "Error syncing from Pseudovision")
        {:status 500 :body {:error (.getMessage e)}}))))

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
           :body {:error (.getMessage e)}}))
      (catch Exception e
        (log/error e "Error migrating catalog IDs")
        {:status 500 :body {:error (.getMessage e)}}))))

(defn audit-tags-handler
  "Audit all tags with LLM and remove unsuitable ones."
  [{:keys [tunabrain catalog]}]
  (fn [_]
    (try
      (let [tags (catalog/get-tags catalog)
            _ (log/info (format "Auditing %d tags" (count tags)))
            {:keys [recommended-for-removal]} (tunabrain/request-tag-audit! tunabrain tags)
            removal-count (count recommended-for-removal)
            removed-count (atom 0)]
        (log/info (format "Tunabrain recommended %d tags for removal" removal-count))
        (if (pos? removal-count)
          (doseq [{:keys [tag reason]} recommended-for-removal]
            (log/info (format "Removing tag '%s': %s" tag reason))
            (catalog/delete-tag! catalog (keyword tag))
            (swap! removed-count inc))
          (log/info "No tags recommended for removal"))
        (log/info (format "Tag audit complete: %d audited, %d removed"
                          (count tags) @removed-count))
        {:status 200
         :body {:tags-audited (count tags)
                :tags-removed @removed-count
                :removed recommended-for-removal}})
      (catch Exception e
        (log/error e "Error during tag audit")
        {:status 500 :body {:error (.getMessage e)}}))))

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
        {:status 500 :body {:error (.getMessage e)}}))))

(defn get-media-by-id-handler
  "Get a specific media item by ID with process timestamps."
  [{:keys [catalog]}]
  (fn [req]
    (try
      (let [media-id (get-in req [:parameters :path :media-id])]
        (if-let [media (catalog/get-media-by-id catalog media-id)]
          {:status 200 :body (serialize-time-fields media)}
          {:status 404 :body {:error (str "Media not found: " media-id)}}))
      (catch Exception e
        (log/error e "Error fetching media by ID")
        {:status 500 :body {:error (.getMessage e)}}))))
