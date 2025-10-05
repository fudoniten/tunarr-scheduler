(ns tunarr.scheduler.http.server
  "HTTP server wiring for the Tunarr Scheduler service."
  (:require [ring.adapter.jetty :as jetty]
            [taoensso.timbre :as log]
            [tunarr.scheduler.http.routes :as routes]))

(defn start!
  [{:keys [port] :as deps}]
  (let [handler (routes/handler deps)
        server (jetty/run-jetty handler {:port port :join? false})]
    (log/info "HTTP server started" {:port port})
    {:server server}))

(defn stop! [{:keys [server]}]
  (when server
    (log/info "Stopping HTTP server")
    (.stop server)))
