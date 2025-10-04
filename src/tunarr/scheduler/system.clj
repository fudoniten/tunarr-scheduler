(ns tunarr.scheduler.system
  "Integrant system definition for the Tunarr Scheduler service."
  (:require [integrant.core :as ig]
            [taoensso.timbre :as log]
            [tunarr.scheduler.http.server :as http]
            [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.scheduling.engine :as engine]
            [tunarr.scheduler.llm :as llm]
            [tunarr.scheduler.tts :as tts]
            [tunarr.scheduler.bumpers :as bumpers]))

(defmethod ig/init-key :tunarr/logger [_ {:keys [level]}]
  (log/set-level! level)
  (log/info "Logger initialised" {:level level})
  {:level level})

(defmethod ig/halt-key! :tunarr/logger [_ _]
  (log/info "Logger shut down"))

(defmethod ig/init-key :tunarr/llm [_ config]
  (llm/create-client config))

(defmethod ig/halt-key! :tunarr/llm [_ client]
  (llm/close! client))

(defmethod ig/init-key :tunarr/tts [_ config]
  (tts/create-client config))

(defmethod ig/halt-key! :tunarr/tts [_ client]
  (tts/close! client))

(defmethod ig/init-key :tunarr/media-source [_ config]
  (catalog/create-catalog config))

(defmethod ig/halt-key! :tunarr/media-source [_ state]
  (catalog/close! state))

(defmethod ig/init-key :tunarr/tunarr-source [_ config]
  config)

(defmethod ig/halt-key! :tunarr/tunarr-source [_ _]
  (log/info "Releasing Tunarr source resources"))

(defmethod ig/init-key :tunarr/persistence [_ config]
  (catalog/create-persistence config))

(defmethod ig/halt-key! :tunarr/persistence [_ state]
  (catalog/close-persistence! state))

(defmethod ig/init-key :tunarr/scheduler [_ {:keys [time-zone daytime-hours seasonal preferences]
                                             :as config}]
  (engine/create-engine (assoc config
                               :time-zone time-zone
                               :daytime-hours daytime-hours
                               :seasonal seasonal
                               :preferences preferences)))

(defmethod ig/halt-key! :tunarr/scheduler [_ engine]
  (engine/stop! engine))

(defmethod ig/init-key :tunarr/bumpers [_ {:keys [llm tts]}]
  (bumpers/create-service {:llm llm :tts tts}))

(defmethod ig/halt-key! :tunarr/bumpers [_ svc]
  (bumpers/close! svc))

(defmethod ig/init-key :tunarr/http-server [_ {:keys [port scheduler media llm tts bumpers tunarr persistence logger]}]
  (http/start! {:port port
                :scheduler scheduler
                :media media
                :llm llm
                :tts tts
                :bumpers bumpers
                :tunarr tunarr
                :persistence persistence}))

(defmethod ig/halt-key! :tunarr/http-server [_ server]
  (http/stop! server))

(defn start
  ([system-config]
   (ig/init system-config))
  ([system-config opts]
   (ig/init system-config opts)))

(defn stop [system]
  (ig/halt! system))
