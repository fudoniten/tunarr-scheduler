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

(defn- parse-catalog-type [value]
  (cond
    (keyword? value) value
    (string? value) (keyword (str/trim value))
    (nil? value) :memory
    :else value))

(defn config->system
  "Produce the Integrant system configuration map from the raw config map."
  [config]
  (let [catalog-config (get config :catalog {})
        catalog-type (parse-catalog-type (or (:type catalog-config)
                                             (:catalog-type config)
                                             (:dbtype catalog-config)
                                             (:dbtype config)))
        catalog-config (-> {:type catalog-type}
                           (merge (dissoc catalog-config :type :dbtype)))
        add-default (fn [cfg k default]
                      (if (contains? cfg k) cfg (assoc cfg k default)))
        catalog-config (if (= :postgresql catalog-type)
                         (-> catalog-config
                             (add-default :dbname (get config :dbname "tunarr-scheduler"))
                             (add-default :user (get config :user "tunarr-scheduler"))
                             (add-default :password (get config :password))
                             (add-default :host (get config :host "postgres"))
                             (add-default :port (get config :port 5432)))
                         catalog-config)]
    {:tunarr/logger {:level (get config :log-level :info)}
     ;:tunarr/llm (:llm config)
     ;:tunarr/tts (:tts config)
     ;:tunarr/media-source (:jellyfin config)
     ;:tunarr/tunarr-source (:tunarr config)
     ;:tunarr/catalog (:catalog config)
     ;:tunarr/scheduler {:time-zone (get-in config [:scheduler :time-zone])
     ;                   :daytime-hours (get-in config [:scheduler :daytime-hours])
     ;                   :seasonal (get-in config [:scheduler :seasonal])
     ;                   :preferences (get-in config [:scheduler :preferences])}
     ;:tunarr/bumpers {:llm (ig/ref :tunarr/llm)
     ;                 :tts (ig/ref :tunarr/tts)}
     :tunarr/catalog catalog-config
     :tunarr/http-server {:port (get-in config [:server :port])
                          ;:scheduler (ig/ref :tunarr/scheduler)
                          ;:media (ig/ref :tunarr/media-source)
                          ;:llm (ig/ref :tunarr/llm)
                          ;:tts (ig/ref :tunarr/tts)
                          ;:bumpers (ig/ref :tunarr/bumpers)
                          ;:tunarr (ig/ref :tunarr/tunarr-source)
                          ;:catalog (ig/ref :tunarr/catalog)
                          :logger (ig/ref :tunarr/logger)}}))
