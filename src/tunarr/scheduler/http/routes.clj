(ns tunarr.scheduler.http.routes
  "Reitit routes for the Tunarr Scheduler API."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [reitit.ring :as ring]
            [ring.util.response :refer [response status content-type]]))

(defn- read-json [request]
  (when-let [body (:body request)]
    (with-open [r (io/reader body)]
      (json/parse-stream r true))))

(defn- ok [data]
  (-> (response (json/generate-string data))
      (status 200)
      (content-type "application/json")))

(defn- accepted [data]
  (-> (response (json/generate-string data))
      (status 202)
      (content-type "application/json")))

(defn rescan-media
  [req]
  (log/info "rescanning media")
  (let [payload (or (read-json req) {})
        result ()]))

(defn handler
  "Create the ring handler for the API."
  [{:keys [media scheduler llm persistence bumpers]}]
  (let [router
        (ring/router
         [["/healthz" {:get (fn [_] (ok {:status "ok"}))}]
          ["/api"
           ["/media/rescan" {:post (fn [req]
                                     (log/info "beginning rescan of media library")
                                     )}]
           #_["/media/retag" {:post (fn [request]
                                      (log/info "Retagging media") 
                                      (let [payload (or (read-json request) {})
                                            result (catalog/tag-media! media llm persistence)]
                                        (accepted {:retagged (count result)})))}]
           #_["/channels/:channel-id/schedule" {:post (fn [{{:keys [channel-id]} :path-params :as request}]
                                                        (log/info "Scheduling channel" {:channel channel-id})
                                                        (let [payload (or (read-json request) {})
                                                              schedule (engine/schedule-week!
                                                                        scheduler llm persistence
                                                                        {:channel-id channel-id
                                                                         :preferences (:preferences payload)})]
                                                          (ok schedule)))}]
           #_["/bumpers/up-next" {:post (fn [request]
                                          (let [payload (read-json request)
                                                bumper (bumpers/generate-bumper!
                                                        bumpers
                                                        {:channel (:channel payload)
                                                         :upcoming (:upcoming payload)})]
                                            (accepted bumper)))}]]])]
    (ring/ring-handler router (ring/create-default-handler))))
