(ns tunarr.scheduler.system
  "Integrant system definition for the Tunarr Scheduler service."
  (:require [integrant.core :as ig]
            [taoensso.timbre :as log]
            [clojure.string :as str]
            [tunarr.scheduler.http.server :as http]
            [tunarr.scheduler.jobs.runner :as job-runner]
            [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.media.sql-catalog]
            [tunarr.scheduler.media.collection :as collection]
            [tunarr.scheduler.media.jellyfin-collection]
            [tunarr.scheduler.curation.tags :as tag-curator]
            [tunarr.scheduler.curation.core :as curation]
            [tunarr.scheduler.jobs.throttler :as job-throttler]
            [tunarr.scheduler.scheduling.engine :as engine]
            [tunarr.scheduler.tunabrain :as tunabrain]
            [tunarr.scheduler.tts :as tts]
            [tunarr.scheduler.bumpers :as bumpers]))

(defmethod ig/init-key :tunarr/logger [_ {:keys [level]}]
  (log/set-level! level)
  (log/info "Logger initialised" {:level level})
  {:level level})

(defmethod ig/halt-key! :tunarr/logger [_ _]
  (log/info "Logger shut down"))

(defmethod ig/init-key :tunarr/tunabrain [_ config]
  (log/info "initializing tunabrain client")
  (tunabrain/create! config))

(defmethod ig/halt-key! :tunarr/tunabrain [_ client]
  (log/info "closing tunabrain client")
  (.close ^java.io.Closeable client))

(defmethod ig/init-key :tunarr/tts [_ config]
  (log/info "initializing tts client")
  #_(tts/create-client config))

(defmethod ig/halt-key! :tunarr/tts [_ client]
  (log/info "closing tts client")
  #_(tts/close! client))

(defmethod ig/init-key :tunarr/job-runner [_ config]
  (log/info "initializing job runner")
  (job-runner/create config))

(defmethod ig/halt-key! :tunarr/job-runner [_ runner]
  (log/info "shutting down job runner")
  (job-runner/shutdown! runner))

(defmethod ig/init-key :tunarr/collection [_ config]
  (log/info "initializing media collection")
  (collection/initialize-collection! config))

(defmethod ig/halt-key! :tunarr/collection [_ collection]
  (log/info "closing media collection")
  (collection/close! collection))

(defmethod ig/init-key :tunarr/tunarr-source [_ config]
  (log/info "initializing media source")
  #_config)

(defmethod ig/halt-key! :tunarr/tunarr-source [_ _]
  (log/info "closing media source")
  #_(log/info "Releasing Tunarr source resources"))

(defmethod ig/init-key :tunarr/catalog [_ config]
  (log/info "initializing catalog")
  (catalog/initialize-catalog! config))

(defmethod ig/halt-key! :tunarr/catalog [_ state]
  (log/info "closing catalog")
  (catalog/close-catalog! state))

(defmethod ig/init-key :tunarr/tunabrain-throttler [_ {:keys [rate queue-size]}]
  (log/info "initializing tunabrain throttler")
  (let [throttler (job-throttler/create :rate rate :queue-size queue-size)]
    (job-throttler/start! throttler)
    throttler))

(defmethod ig/halt-key! :tunarr/tunabrain-throttler [_ throttler]
  (log/info "closing tunabrain throttler")
  (job-throttler/stop! throttler))

(defmethod ig/init-key :tunarr/curation
  [_ {:keys [tunabrain catalog throttler libraries config]}]
  (log/info "starting curator")
  (let [curator (curation/create! {:tunabrain tunabrain
                                   :catalog   catalog
                                   :throttler throttler
                                   :config    config})]
    (curation/start! curator libraries)
    curator))

(defmethod ig/halt-key! :tunarr/curation
  [_ curator]
  (curation/stop! curator))

(defmethod ig/init-key :tunarr/config-sync [_ {:keys [channels libraries catalog]}]
  (when (not channels)
    (throw (ex-info "missing required key: channels" {})))
  (when (not libraries)
    (throw (ex-info "missing required key: libraries" {})))
  (log/info (format "syncing channels with config: %s"
                    (str/join "," (map name (keys channels)))))
  (catalog/update-channels! catalog channels)
  (log/info (format "syncing libraries with config: %s"
                    (str/join "," (map name (keys libraries)))))
  (catalog/update-libraries! catalog libraries)
  channels)

(defmethod ig/init-key :tunarr/normalize-tags
  [_ {:keys [catalog tag-config]}]
  (tag-curator/normalize! catalog tag-config))

(defmethod ig/halt-key! :tunarr/normalize-tags
  [_]
  nil)

(defmethod ig/halt-key! :tunarr/config-sync [_ _]
  (log/info "shutting down channel sync")
  nil)

(defmethod ig/init-key :tunarr/scheduler [_ {:keys [time-zone daytime-hours seasonal preferences]
                                             :as config}]
  (log/info "initializing scheduler")
  #_(engine/create-engine (assoc config
                                 :time-zone time-zone
                                 :daytime-hours daytime-hours
                                 :seasonal seasonal
                                 :preferences preferences)))

(defmethod ig/halt-key! :tunarr/scheduler [_ engine]
  (log/info "closing scheduler")
  #_(engine/stop! engine))

(defmethod ig/init-key :tunarr/bumpers [_ {:keys [tunabrain tts]}]
  (log/info "initializing bumpers")
  #_(bumpers/create-service {:tunabrain tunabrain :tts tts}))

(defmethod ig/halt-key! :tunarr/bumpers [_ svc]
  (log/info "closing bumpers")
  #_(bumpers/close! svc))

(defmethod ig/init-key :tunarr/http-server [_ {:keys [port scheduler media tts bumpers tunarr catalog logger job-runner collection tunabrain]}]
  (http/start! {:port port
                :job-runner job-runner
                :collection collection
                :catalog catalog
                :tunabrain tunabrain
                ;:scheduler scheduler
                ;:media media
                ;:tts tts
                ;:bumpers bumpers
                ;:tunarr tunarr
                ;:catalog catalog
                }))

(defmethod ig/halt-key! :tunarr/http-server [_ server]
  (http/stop! server))

(defn start
  ([system-config]
   (ig/init system-config))
  ([system-config opts]
   (ig/init system-config opts)))

(defn stop [system]
  (ig/halt! system))
