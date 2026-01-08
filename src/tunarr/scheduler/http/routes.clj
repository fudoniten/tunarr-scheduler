(ns tunarr.scheduler.http.routes
  "Reitit routes for the Tunarr Scheduler API."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [reitit.ring :as ring]
            [ring.util.response :refer [response status content-type]]
            [taoensso.timbre :as log]
            [tunarr.scheduler.jobs.runner :as jobs]
            [tunarr.scheduler.media.sync :as media-sync]
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

(defn- submit-rescan-job!
  [{:keys [job-runner collection catalog]} {:keys [library]}]
  (if-not library
    (bad-request "library not specified for rescan")
    (let [job (jobs/submit! job-runner
                            {:type :media/rescan
                             :metadata {:library library}}
                            (fn [report-progress]
                              (media-sync/rescan-library! collection
                                                          catalog
                                                          {:library         library
                                                           :report-progress report-progress})))]
      (accepted {:job job}))))

(defn- submit-retag-job!
  [{:keys [job-runner catalog]} {:keys [library]}]
  (if-not library
    (bad-request "library not specified for retag")
    (let [job (jobs/submit! job-runner
                            {:type :media/rescan
                             :metadata {:library library}}
                            (fn [report-progress]
                              (curate/retag-library! catalog
                                                     {:library         library
                                                      :report-progress report-progress})))]
      (accepted {:job job}))))

(defn- submit-tagline-job!
  [{:keys [job-runner catalog]} {:keys [library]}]
  (if-not library
    (bad-request "library not specified for taglines")
    (let [job (jobs/submit! job-runner
                            {:type :media/rescan
                             :metadata {:library library}}
                            (fn [report-progress]
                              (curate/generate-library-taglines! catalog
                                                                 {:library         library
                                                                  :report-progress report-progress})))]
      (accepted {:job job}))))

#_(defn- submit-recategorize-job!
  [{:keys [job-runner catalog]} {:keys [library]}]
  (if-not library
    (bad-request "library not specified for rescan")
    (let [job (jobs/submit! job-runner
                            {:type :media/rescan
                             :metadata {:library library}}
                            (fn [report-progress]
                              (curate/recategorize-library! catalog
                                                            {:library         library
                                                             :report-progress report-progress})))]
      (accepted {:job job}))))

(defn- audit-tags!
  "Audit all tags with Tunabrain and remove unsuitable ones."
  [{:keys [tunabrain catalog]}]
  (try
    (let [tags (catalog/get-tags catalog)
          _ (log/info (format "Auditing %d tags" (count tags)))
          {:keys [recommended-for-removal]} (tunabrain/request-tag-audit! tunabrain tags)
          removed-count (atom 0)]
      (doseq [{:keys [tag reason]} recommended-for-removal]
        (log/info (format "Removing tag '%s': %s" tag reason))
        (catalog/delete-tag! catalog (keyword tag))
        (swap! removed-count inc))
      (ok {:tags-audited (count tags)
           :tags-removed @removed-count
           :removed recommended-for-removal}))
    (catch Exception e
      (log/error e "Error during tag audit")
      (json-response {:error (.getMessage e)} 500))))

(defn handler
  "Create the ring handler for the API."
  [{:keys [job-runner collection catalog tunabrain]}]
  (let [router
        (ring/router
         [["/healthz" {:get (fn [_] (ok {:status "ok"}))}]
          ["/api"
           ["/media/:library/rescan" {:post (fn [{{:keys [library]} :path-params}]
                                              (submit-rescan-job!
                                               {:job-runner job-runner
                                                :collection collection
                                                :catalog    catalog}
                                               {:library    library}))}]
           ["/media/:library/retag" {:post (fn [{{:keys [library]} :path-params}]
                                             (submit-retag-job!
                                              {:job-runner job-runner
                                               :catalog    catalog}
                                              {:library    library}))}]
           ["/media/:library/add-taglines" {:post (fn [{{:keys [library]} :path-params}]
                                                    (submit-tagline-job!
                                                     {:job-runner job-runner
                                                      :catalog    catalog}
                                                     {:library    library}))}]
           #_["/media/:library/recategorize" {:post (fn [{{:keys [library]} :path-params}]
                                                    (submit-recategorize-job!
                                                     {:job-runner job-runner
                                                      :catalog    catalog}
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
