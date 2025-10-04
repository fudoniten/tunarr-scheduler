(ns tunarr.scheduler.http.routes
  "Reitit routes for the Tunarr Scheduler API."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [reitit.ring :as ring]
            [ring.util.response :as response]
            [taoensso.timbre :as log]
            [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.scheduling.engine :as engine]
            [tunarr.scheduler.bumpers :as bumpers]))

(defn- read-json [request]
  (when-let [body (:body request)]
    (with-open [r (io/reader body)]
      (json/parse-stream r true))))

(defn- ok [data]
  (-> (response/response (json/generate-string data))
      (response/status 200)
      (response/content-type "application/json")))

(defn- accepted [data]
  (-> (response/response (json/generate-string data))
      (response/status 202)
      (response/content-type "application/json")))

(defn handler
  "Create the ring handler for the API."
  [{:keys [media scheduler llm persistence bumpers]}]
  (let [router
        (ring/router
         [["/healthz" {:get (fn [_]
                               (ok {:status "ok"}))}]
          ["/api"
           ["/media/retag" {:post (fn [request]
                                     (log/info "Retagging media")
                                     (let [payload (or (read-json request) {})
                                           result (catalog/tag-media! media llm persistence)]
                                       (accepted {:retagged (count result)})))}]
           ["/channels/:channel-id/schedule" {:post (fn [{{:keys [channel-id]} :path-params :as request}]
                                                    (log/info "Scheduling channel" {:channel channel-id})
                                                    (let [payload (or (read-json request) {})
                                                          schedule (engine/schedule-week!
                                                                    scheduler llm persistence
                                                                    {:channel-id channel-id
                                                                     :preferences (:preferences payload)})]
                                                      (ok schedule)))}]
           ["/bumpers/up-next" {:post (fn [request]
                                         (let [payload (read-json request)
                                               bumper (bumpers/generate-bumper!
                                                        bumpers
                                                        {:channel (:channel payload)
                                                         :upcoming (:upcoming payload)})]
                                           (accepted bumper)))}]]])]
    (ring/ring-handler router (ring/create-default-handler))))
