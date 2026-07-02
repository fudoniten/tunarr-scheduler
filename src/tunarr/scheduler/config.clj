(ns tunarr.scheduler.config
  "Configuration loading utilities for the Tunarr Scheduler service."
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [integrant.core :as ig]
            [tunarr.scheduler.media :as media]))

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

(defn update-key [m k new-k f]
  (assoc m new-k (f (get m k))))

(defn- replace-envvar
  "Override key `k` in `cfg` with the value of `envvar` when it is set."
  [cfg k envvar]
  (if-let [val (System/getenv envvar)]
    (assoc cfg k val)
    cfg))

(defn- add-default [cfg k default]
  (if (contains? cfg k) cfg (assoc cfg k default)))

(defn- resolve-collection-config
  "Resolve the :collection config, applying env-var overrides per backend
  type. Pseudovision collections fall back to [:pseudovision :base-url]
  and must end up with a :base-url."
  [config]
  (let [collection-config (get config :collection {})]
    (case (:type collection-config)
      :jellyfin (-> collection-config
                    (replace-envvar :api-key  "COLLECTION_API_KEY")
                    (replace-envvar :base-url "COLLECTION_BASE_URL"))
      :pseudovision (let [resolved (-> collection-config
                                       (update :base-url #(or % (get-in config [:pseudovision :base-url])))
                                       (replace-envvar :base-url "PSEUDOVISION_URL")
                                       (replace-envvar :base-url "COLLECTION_BASE_URL"))]
                      (when (nil? (:base-url resolved))
                        (throw (ex-info (str "Pseudovision collection requires :base-url. "
                                             "Set :base-url under :collection or :pseudovision in your config, "
                                             "or set the PSEUDOVISION_URL / COLLECTION_BASE_URL environment variable.")
                                        {:collection-config resolved})))
                      resolved)
      collection-config)))

(defn- resolve-catalog-config
  "Resolve the :catalog config: normalize :type (accepting the legacy
  :catalog-type / :dbtype spellings) and, for Postgres, apply env-var
  overrides and connection defaults."
  [config]
  (let [catalog-config (get config :catalog {})
        catalog-type (parse-catalog-type (or (:type catalog-config)
                                             (:catalog-type config)
                                             (:dbtype catalog-config)
                                             (:dbtype config)))
        catalog-config (merge {:type catalog-type}
                              (dissoc catalog-config :type))]
    (if (= :postgresql catalog-type)
      (-> catalog-config
          (replace-envvar :dbname   "CATALOG_DATABASE")
          (replace-envvar :user     "CATALOG_USER")
          (replace-envvar :password "CATALOG_PASSWORD")
          (replace-envvar :host     "CATALOG_HOST")
          (replace-envvar :port     "CATALOG_PORT")
          (add-default :dbname "tunarr-scheduler")
          (add-default :user   "tunarr-scheduler")
          (add-default :host   "postgres")
          (add-default :port   5432))
      catalog-config)))

(defn- resolve-channel-config
  "Rename per-channel config keys to their namespaced media equivalents."
  [config]
  (into {}
        (map (fn [[ch cfg]]
               [ch (-> cfg
                       (update-key :id ::media/channel-id identity)
                       (update-key :description ::media/channel-description identity)
                       (update-key :name ::media/channel-fullname identity))]))
        (get config :channels {})))

(defn- resolve-bumpers-config
  "Resolve bumper config, applying env-var overrides."
  [config]
  (-> (get config :bumpers {})
      (replace-envvar :music-library-dir "BUMPER_MUSIC_DIR")))

(defn config->system
  "Produce the Integrant system configuration map from the raw config map."
  [config]
  (log/info (format "full configuration: %s" config))
  (let [collection-config (resolve-collection-config config)
        catalog-config (resolve-catalog-config config)
        curation-config (get config :curation)
        tag-config (get config :tag-config {})
        categories-config (get config :categories {})
        channel-config (resolve-channel-config config)
        backends-config (get config :backends {})
        pseudovision-config (get config :pseudovision {})
        bumpers-config (resolve-bumpers-config config)]
    {:tunarr/logger {:level (get config :log-level :info)}
     :tunarr/job-runner (get config :jobs {})
     :tunarr/tunabrain-throttler (get-in config [:tunabrain :throttler])
     :tunarr/tunabrain (:tunabrain config)
     :tunarr/pseudovision pseudovision-config
     :tunarr/backends backends-config
     :tunarr/llm (get config :llm {:provider :mock})
     ;; TODO: Add tts, media-source, tunarr-source, scheduler configs when implemented
     :tunarr/bumpers (assoc bumpers-config
                            :tunabrain (ig/ref :tunarr/tunabrain)
                            :jellyfin (:jellyfin config)
                            :pseudovision-url (get-in config [:pseudovision :base-url]))
     :tunarr/collection collection-config
     :tunarr/catalog catalog-config
     :tunarr/curation {:libraries (keys (get collection-config :libraries))
                       :tunabrain (ig/ref :tunarr/tunabrain)
                       :catalog   (ig/ref :tunarr/catalog)
                       :throttler (ig/ref :tunarr/tunabrain-throttler)
                       :config    (merge curation-config
                                         {:libraries (keys (:libraries collection-config))
                                          :channels  channel-config
                                          :categories categories-config})}
     :tunarr/config-sync {:channels channel-config
                          :collection-config collection-config
                          :catalog (ig/ref :tunarr/catalog)}
     :tunarr/normalize-tags {:catalog (ig/ref :tunarr/catalog)
                             :tag-config tag-config}
     :tunarr/http-server {:port (-> (System/getenv "TUNARR_SCHEDULER_PORT")
                                    (or (get-in config [:server :port]))
                                    (parse-port))
                          :job-runner (ig/ref :tunarr/job-runner)
                          :tunabrain (ig/ref :tunarr/tunabrain)
                          :throttler (ig/ref :tunarr/tunabrain-throttler)
                          :llm (ig/ref :tunarr/llm)
                          :collection (ig/ref :tunarr/collection)
                          :catalog (ig/ref :tunarr/catalog)
                          :backends (ig/ref :tunarr/backends)
                          :pseudovision (ig/ref :tunarr/pseudovision)
                          :channels channel-config
                          :logger (ig/ref :tunarr/logger)
                          :curation-config (merge curation-config
                                                  {:channels  channel-config
                                                   :categories categories-config})
                          :bumpers (ig/ref :tunarr/bumpers)}}))
