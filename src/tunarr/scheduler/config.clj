(ns tunarr.scheduler.config
  "Configuration loading utilities for the Tunarr Scheduler service."
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [integrant.core :as ig]))

(def default-config-resource "config.edn")

(defn- parse-port [value]
  (cond
    (int? value) value
    (string? value) (Integer/parseInt (str/trim value))
    :else (throw (ex-info "Unsupported port value" {:value value}))))

(defn load-config
  "Load configuration from the provided path (resource or file). Returns a map
  with normalized values suitable for the runtime system."
  [path]
  (let [config (aero/read-config (io/file path))]
     (-> config
         (update-in [:server :port] parse-port))))

(defn- parse-catalog-type [value]
  (cond
    (keyword? value) value
    (string? value) (keyword (str/trim value))
    (nil? value) :memory
    :else value))

(defn config->system
  "Produce the Integrant system configuration map from the raw config map."
  [config]
  (log/info (format "full configuration: %s" config))
  (let [catalog-config (get config :catalog {})
        catalog-type (parse-catalog-type (or (:type catalog-config)
                                             (:catalog-type config)
                                             (:dbtype catalog-config)
                                             (:dbtype config)))
        catalog-config (-> {:type catalog-type}
                           (merge catalog-config))
        replace-envvar (fn [cfg k envvar]
                         (if-let [val (System/getenv envvar)]
                           (assoc cfg k val)
                           cfg))
        collection-config (get config :collection {})
        add-default (fn [cfg k default]
                      (if (contains? cfg k) cfg (assoc cfg k default)))
        collection-config (if (= :jellyfin (-> collection-config :type))
                            (-> collection-config
                                (replace-envvar :api-key "COLLECTION_API_KEY")
                                (replace-envvar :base-url "COLLECTION_BASE_URL"))
                            collection-config)
        catalog-config (if (= :postgresql catalog-type)
                         (-> catalog-config
                             (replace-envvar :database "CATALOG_DATABASE")
                             (replace-envvar :user     "CATALOG_USER")
                             (replace-envvar :password "CATALOG_PASSWORD")
                             (replace-envvar :host     "CATALOG_HOST")
                             (replace-envvar :port     "CATALOG_PORT")
                             (add-default :database (get config :dbname "tunarr-scheduler"))
                             (add-default :user     (get config :user "tunarr-scheduler"))
                             (add-default :password (get config :password))
                             (add-default :host     (get config :host "postgres"))
                             (add-default :port     (get config :port 5432)))
                         catalog-config)]
    {:tunarr/logger {:level (get config :log-level :info)}
     :tunarr/job-runner (get config :jobs {})
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
     :tunarr/collection collection-config
     :tunarr/catalog catalog-config
     :tunarr/http-server {:port (-> (System/getenv "TUNARR_SCHEDULER_PORT")
                                    (or (get-in config [:server :port]))
                                    (parse-port))
                          :job-runner (ig/ref :tunarr/job-runner)
                                        ;:scheduler (ig/ref :tunarr/scheduler)
                                        ;:media (ig/ref :tunarr/media-source)
                                        ;:llm (ig/ref :tunarr/llm)
                                        ;:tts (ig/ref :tunarr/tts)
                                        ;:bumpers (ig/ref :tunarr/bumpers)
                                        ;:tunarr (ig/ref :tunarr/tunarr-source)
                          :collection (ig/ref :tunarr/collection)
                          :catalog (ig/ref :tunarr/catalog)
                          :logger (ig/ref :tunarr/logger)}}))
