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
            [tunarr.scheduler.scheduling.engine :as engine]
            [tunarr.scheduler.llm :as llm]
            [tunarr.scheduler.llm.openai]
            [tunarr.scheduler.tts :as tts]
            [tunarr.scheduler.bumpers :as bumpers]))

(defmethod ig/init-key :tunarr/logger [_ {:keys [level]}]
  (log/set-level! level)
  (log/info "Logger initialised" {:level level})
  {:level level})

(defmethod ig/halt-key! :tunarr/logger [_ _]
  (log/info "Logger shut down"))

(defmethod ig/init-key :tunarr/llm [_ config]
  (log/info "initializing llm client")
  #_(llm/create-client config))

(defmethod ig/halt-key! :tunarr/llm [_ client]
  (log/info "closing llm client")
  #_(llm/close! client))

(defmethod ig/init-key :tunarr/tts [_ config]
  (log/info "initializing tts client")
  #_(tts/create-client config))

(defmethod ig/halt-key! :tunarr/tts [_ client]
  (log/info "initializing tts client")
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

(defmethod ig/init-key :tunarr/config-sync [_ {:keys [channels libraries catalog]}]
  (when (not channels)
    (throw (ex-info "missing required key: channels" {})))
  (when (not libraries)
    (throw (ex-info "missing required key: libraries" {})))
  (log/info (format "syncing channels with config: %s"
                    (str/join "," (map name (keys channels)))))
  (catalog/update-channels catalog channels)
  (log/info (format "syncing libraries with config: %s"
                    (str/join "," (map name (keys libraries)))))
  (catalog/update-libraries catalog libraries)
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

(defmethod ig/init-key :tunarr/bumpers [_ {:keys [llm tts]}]
  (log/info "initializing bumpers")
  #_(bumpers/create-service {:llm llm :tts tts}))

(defmethod ig/halt-key! :tunarr/bumpers [_ svc]
  (log/info "closing bumpers")
  #_(bumpers/close! svc))

(defmethod ig/init-key :tunarr/http-server [_ {:keys [port scheduler media llm tts bumpers tunarr catalog logger job-runner collection]}]
  (http/start! {:port port
                :job-runner job-runner
                :collection collection
                :catalog catalog
                ;:scheduler scheduler
                ;:media media
                ;:llm llm
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
