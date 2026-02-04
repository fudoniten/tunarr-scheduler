(ns tunarr.scheduler.http.routes
  "Reitit routes for the Tunarr Scheduler API."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [reitit.ring :as ring]
            [ring.util.response :refer [response status content-type]]
            [taoensso.timbre :as log]
            [tunarr.scheduler.jobs.runner :as jobs]
            [tunarr.scheduler.media.sync :as media-sync]
            [tunarr.scheduler.media.jellyfin-sync :as jellyfin-sync]
            [tunarr.scheduler.curation.core :as curate]
            [tunarr.scheduler.tunabrain :as tunabrain]
            [tunarr.scheduler.media.catalog :as catalog]))

(defn- read-json [request]
  (when-let [body (:body request)]
    (with-open [r (io/reader body)]
      (json/parse-stream r true))))

(defn- json-response [data status-code]
  (-> (response (json/generate-string data))
      (status status-code)
      (content-type "application/json")))

(defn- ok [data]
  (json-response data 200))

(defn- accepted [data]
  (json-response data 202))

(defn- bad-request [message]
  (json-response {:error message} 400))

(defn- not-found [message]
  (json-response {:error message} 404))

(defn- submit-job!
  "Generic job submission handler."
  [job-runner job-type library error-msg job-fn]
  (if-not library
    (bad-request error-msg)
    (let [job (jobs/submit! job-runner
                            {:type job-type
                             :metadata {:library library}}
                            (fn [report-progress]
                              (job-fn {:library         library
                                       :report-progress report-progress})))]
      (accepted {:job job}))))

(defn- submit-rescan-job!
  [{:keys [job-runner collection catalog]} {:keys [library]}]
  (try
    (submit-job! job-runner
                 :media/rescan
                 library
                 "library not specified for rescan"
                 (fn [opts] (media-sync/rescan-library! collection catalog opts)))
    (catch Exception e
      (log/error e "Error submitting rescan job" {:library library})
      (json-response {:error (.getMessage e)} 500))))

(defn- submit-retag-job!
  [{:keys [job-runner catalog tunabrain throttler config]} {:keys [library]}]
  (try
    (submit-job! job-runner
                 :media/retag
                 library
                 "library not specified for retag"
                 (fn [opts] (curate/retag-library! 
                              (curate/->TunabrainCuratorBackend 
                                tunabrain catalog throttler config)
                              library)))
    (catch Exception e
      (log/error e "Error submitting retag job" {:library library})
      (json-response {:error (.getMessage e)} 500))))

(defn- submit-tagline-job!
  [{:keys [job-runner catalog tunabrain throttler config]} {:keys [library]}]
  (try
    (submit-job! job-runner
                 :media/taglines
                 library
                 "library not specified for taglines"
                 (fn [opts] (curate/generate-library-taglines! 
                              (curate/->TunabrainCuratorBackend 
                                tunabrain catalog throttler config)
                              library)))
    (catch Exception e
      (log/error e "Error submitting tagline job" {:library library})
      (json-response {:error (.getMessage e)} 500))))

(defn- submit-recategorize-job!
  [{:keys [job-runner catalog tunabrain throttler config]} {:keys [library]}]
  (try
    (submit-job! job-runner
                 :media/recategorize
                 library
                 "library not specified for recategorize"
                 (fn [opts] (curate/recategorize-library! 
                              (curate/->TunabrainCuratorBackend 
                                tunabrain catalog throttler config)
                              library)))
    (catch Exception e
      (log/error e "Error submitting recategorize job" {:library library})
      (json-response {:error (.getMessage e)} 500))))

(defn- submit-jellyfin-sync-job!
  [{:keys [job-runner catalog jellyfin-config]} {:keys [library]}]
  (try
    (submit-job! job-runner
                 :media/jellyfin-sync
                 library
                 "library not specified for jellyfin sync"
                 (fn [opts] (jellyfin-sync/sync-library-tags! catalog jellyfin-config library opts)))
    (catch Exception e
      (log/error e "Error submitting Jellyfin sync job" {:library library})
      (json-response {:error (.getMessage e)} 500))))

(defn- audit-tags!
  "Audit all tags with Tunabrain and remove unsuitable ones."
  [{:keys [tunabrain catalog]}]
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
      (ok {:tags-audited (count tags)
           :tags-removed @removed-count
           :removed recommended-for-removal}))
    (catch Exception e
      (log/error e "Error during tag audit")
      (json-response {:error (.getMessage e)} 500))))

(defn- list-libraries!
  "List all configured libraries."
  [{:keys [jellyfin-config]}]
  (try
    (let [libraries (:libraries jellyfin-config)]
      (if libraries
        (ok {:libraries (into {}
                             (map (fn [[name id]]
                                    [(clojure.core/name name)
                                     {:name (clojure.core/name name)
                                      :id id}])
                                  libraries))})
        (ok {:libraries {}})))
    (catch Exception e
      (log/error e "Error listing libraries")
      (json-response {:error (.getMessage e)} 500))))

(defn handler
  "Create the ring handler for the API."
  [{:keys [job-runner collection catalog tunabrain throttler curation-config jellyfin-config]}]
  (let [router
        (ring/router
         [["/healthz" {:get (fn [_] (ok {:status "ok"}))}]
          ["/api"
           ["/media/libraries" {:get (fn [_]
                                       (list-libraries!
                                        {:jellyfin-config jellyfin-config}))}]
           ["/media/:library/rescan" {:post (fn [{{:keys [library]} :path-params}]
                                              (submit-rescan-job!
                                               {:job-runner job-runner
                                                :collection collection
                                                :catalog    catalog}
                                               {:library    library}))}]
           ["/media/:library/retag" {:post (fn [{{:keys [library]} :path-params}]
                                             (submit-retag-job!
                                              {:job-runner job-runner
                                               :catalog    catalog
                                               :tunabrain  tunabrain
                                               :throttler  throttler
                                               :config     curation-config}
                                              {:library    library}))}]
            ["/media/:library/add-taglines" {:post (fn [{{:keys [library]} :path-params}]
                                                     (submit-tagline-job!
                                                      {:job-runner job-runner
                                                       :catalog    catalog
                                                       :tunabrain  tunabrain
                                                       :throttler  throttler
                                                       :config     curation-config}
                                                      {:library    library}))}]
            ["/media/:library/recategorize" {:post (fn [{{:keys [library]} :path-params}]
                                                     (submit-recategorize-job!
                                                      {:job-runner job-runner
                                                       :catalog    catalog
                                                       :tunabrain  tunabrain
                                                       :throttler  throttler
                                                       :config     curation-config}
                                                      {:library    library}))}]
            ["/media/:library/sync-jellyfin-tags" {:post (fn [{{:keys [library]} :path-params}]
                                                            (submit-jellyfin-sync-job!
                                                             {:job-runner job-runner
                                                              :catalog    catalog
                                                              :jellyfin-config jellyfin-config}
                                                             {:library    library}))}]
           ["/media/tags/audit" {:post (fn [_]
                                         (audit-tags!
                                          {:tunabrain tunabrain
                                           :catalog   catalog}))}]
           ["/jobs" {:get (fn [_]
                            (ok {:jobs (jobs/list-jobs job-runner)}))}]
           ["/jobs/:job-id" {:get (fn [{{:keys [job-id]} :path-params}]
                                    (if-let [job (jobs/job-info job-runner job-id)]
                                      (ok {:job job})
                                      (not-found "Job not found")))}]]])]
    (ring/ring-handler router (ring/create-default-handler))))
