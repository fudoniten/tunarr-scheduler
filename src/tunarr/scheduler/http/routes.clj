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
  [{:keys [job-runner collection catalog]} payload]
  (let [libraries (or (:libraries payload)
                      (some-> (:library payload) vector))]
    (if (seq libraries)
      (let [job (jobs/submit! job-runner
                              {:type :media/rescan
                               :metadata {:libraries libraries}}
                              #(media-sync/rescan-libraries!
                                collection catalog {:libraries libraries}))]
        (accepted {:job job}))
      (bad-request "At least one library must be provided"))))

(defn handler
  "Create the ring handler for the API."
  [{:keys [job-runner collection catalog]}]
  (let [router
        (ring/router
         [["/healthz" {:get (fn [_] (ok {:status "ok"}))}]
          ["/api"
           ["/media/rescan" {:post (fn [req]
                                      (log/info "Scheduling media rescan")
                                      (let [payload (or (read-json req) {})]
                                        (submit-rescan-job!
                                         {:job-runner job-runner
                                          :collection collection
                                          :catalog catalog}
                                         payload)))}]
           ["/jobs" {:get (fn [_]
                             (ok {:jobs (jobs/list-jobs job-runner)}))}]
           ["/jobs/:job-id" {:get (fn [{{:keys [job-id]} :path-params}]
                                     (if-let [job (jobs/job-info job-runner job-id)]
                                       (ok {:job job})
                                       (not-found "Job not found")))}]]])]
    (ring/ring-handler router (ring/create-default-handler))))
