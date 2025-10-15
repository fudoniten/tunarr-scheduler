(ns tunarr.scheduler.config
  "Configuration loading utilities for the Tunarr Scheduler service."
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [integrant.core :as ig]))

(def default-config-resource "config.edn")

(defn- parse-port [value]
  (cond
    (int? value) value
    (string? value) (Integer/parseInt (str/trim value))
    :else (throw (ex-info "Unsupported port value" {:value value}))))

(defn load-config
  "Load configuration from the provided path (resource or file). When `path`
   is nil, the default `resources/config.edn` file is used. Returns a map with
   normalized values suitable for the runtime system."
  ([]
   (load-config nil))
   ([path]
    (let [source (if path (io/file path) (io/resource default-config-resource))
          config (aero/read-config source)]
     (-> config
         (update-in [:server :port] parse-port)))))

(defn config->system
  "Produce the Integrant system configuration map from the raw config map."
  [config]
  {:tunarr/logger {:level (get config :log-level :info)}
   ;:tunarr/llm (:llm config)
   ;:tunarr/tts (:tts config)
   ;:tunarr/media-source (:jellyfin config)
   ;:tunarr/tunarr-source (:tunarr config)
   ;:tunarr/persistence (:persistence config)
   ;:tunarr/scheduler {:time-zone (get-in config [:scheduler :time-zone])
   ;                   :daytime-hours (get-in config [:scheduler :daytime-hours])
   ;                   :seasonal (get-in config [:scheduler :seasonal])
   ;                   :preferences (get-in config [:scheduler :preferences])}
   ;:tunarr/bumpers {:llm (ig/ref :tunarr/llm)
   ;                 :tts (ig/ref :tunarr/tts)}
   :tunarr/catalog {:dbtype   (get config :dbtype :in-memory)
                    :dbname   (get config :dbname "tunarr-scheduler")
                    :user     (get config :user   "tunarr-scheduler")
                    :password (get config :password)
                    :host     (get config :host   "postgres")
                    :port     (get config :port   5432)}
   :tunarr/http-server {:port (get-in config [:server :port])
                        ;:scheduler (ig/ref :tunarr/scheduler)
                        ;:media (ig/ref :tunarr/media-source)
                        ;:llm (ig/ref :tunarr/llm)
                        ;:tts (ig/ref :tunarr/tts)
                        ;:bumpers (ig/ref :tunarr/bumpers)
                        ;:tunarr (ig/ref :tunarr/tunarr-source)
                        ;:persistence (ig/ref :tunarr/persistence)
                        :logger (ig/ref :tunarr/logger)}})
