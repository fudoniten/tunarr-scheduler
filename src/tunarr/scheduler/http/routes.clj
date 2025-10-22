(ns tunarr.scheduler.http.routes
  "Reitit routes for the Tunarr Scheduler API."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [reitit.ring :as ring]
            [ring.util.response :refer [response status content-type]]
            [taoensso.timbre :as log]
            [tunarr.scheduler.jobs.runner :as jobs]
            [tunarr.scheduler.media.sync :as media-sync]))

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

(defn handler
  "Create the ring handler for the API."
  [{:keys [job-runner collection catalog]}]
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
           ["/jobs" {:get (fn [_]
                            (ok {:jobs (jobs/list-jobs job-runner)}))}]
           ["/jobs/:job-id" {:get (fn [{{:keys [job-id]} :path-params}]
                                     (if-let [job (jobs/job-info job-runner job-id)]
                                       (ok {:job job})
                                       (not-found "Job not found")))}]]])]
    (ring/ring-handler router (ring/create-default-handler))))
