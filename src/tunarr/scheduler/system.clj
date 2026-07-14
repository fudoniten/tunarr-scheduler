(ns tunarr.scheduler.system
  (:require [integrant.core :as ig]
            [taoensso.timbre :as log]
            [clojure.string :as str]
            [tunarr.scheduler.http.server :as http]
            [tunarr.scheduler.jobs.runner :as job-runner]
            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.media.sql-catalog]
            [tunarr.scheduler.media.memory-catalog]
            [tunarr.scheduler.media.collection :as collection]
            [tunarr.scheduler.media.pseudovision-autosync :as pv-autosync]
            [tunarr.scheduler.media.pseudovision-collection]
            [tunarr.scheduler.curation.tags :as tag-curator]
            [tunarr.scheduler.curation.core :as curation]
            [tunarr.scheduler.jobs.throttler :as job-throttler]
            [tunarr.scheduler.tunabrain :as tunabrain]
            [tunarr.scheduler.llm :as llm]
            [tunarr.scheduler.bumpers :as bumpers]
            [tunarr.scheduler.backends.pseudovision.client :as pseudovision]))

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

;; TODO: Implement TTS client when text-to-speech functionality is needed
(defmethod ig/init-key :tunarr/tts [_ config]
  (log/info "tts client initialization disabled (not yet implemented)")
  nil)

(defmethod ig/halt-key! :tunarr/tts [_ client]
  (log/info "tts client shutdown disabled (not yet implemented)")
  nil)

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

;; TODO: Implement Tunarr source when needed for pulling data from Tunarr API
(defmethod ig/init-key :tunarr/tunarr-source [_ config]
  (log/info "tunarr source initialization disabled (not yet implemented)")
  nil)

(defmethod ig/halt-key! :tunarr/tunarr-source [_ _]
  (log/info "tunarr source shutdown disabled (not yet implemented)")
  nil)

(defmethod ig/init-key :tunarr/raw-catalog [_ config]
  (log/info "initializing catalog")
  (catalog/initialize-catalog! config))

(defmethod ig/halt-key! :tunarr/raw-catalog [_ state]
  (log/info "closing catalog")
  (catalog/close-catalog! state))

(defn- ->long
  "Coerce a config value that may arrive as an int or (via an env override) a
   string into a long. Returns `default` for nil/blank/unparseable values."
  [v default]
  (cond
    (number? v) (long v)
    (string? v) (try (Long/parseLong (str/trim v)) (catch Exception _ default))
    :else default))

;; The :tunarr/catalog component is what every other component depends on. It
;; wraps the raw catalog with the Pseudovision auto-sync decorator when
;; auto-sync is enabled and Pseudovision is configured, so tag/category edits
;; propagate immediately (see media.pseudovision-autosync). When disabled, it
;; is the raw catalog verbatim — no worker, no overhead.
(defmethod ig/init-key :tunarr/catalog [_ {:keys [catalog pseudovision config]}]
  (if (and pseudovision (:auto-sync config))
    (do
      (log/info "initializing Pseudovision auto-sync (event-driven tag sync)")
      (let [reconcile-hours (->long (:sync-reconcile-hours config 24) 24)
            worker (-> {:pseudovision   pseudovision
                        :catalog        catalog
                        :debounce-ms    (->long (:sync-debounce-ms config 3000) 3000)
                        :bulk-threshold (->long (:sync-bulk-threshold config 50) 50)
                        :backoff-ms     (->long (:sync-backoff-ms config 30000) 30000)
                        :reconcile-ms   (when (pos? reconcile-hours)
                                          (* reconcile-hours 60 60 1000))}
                       pv-autosync/create
                       pv-autosync/start!)]
        (pv-autosync/wrap-catalog catalog worker)))
    (do
      (log/info "Pseudovision auto-sync disabled - using raw catalog")
      catalog)))

(defmethod ig/halt-key! :tunarr/catalog [_ catalog]
  (when-let [worker (pv-autosync/wrapped-worker catalog)]
    (log/info "stopping Pseudovision auto-sync worker")
    (pv-autosync/stop! worker)))

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
  (log/warn "constant curation not enabled for now")
  #_(let [curator (curation/create! {:tunabrain tunabrain
                                     :catalog   catalog
                                     :throttler throttler
                                     :config    config})]
      (curation/start! curator libraries)
      curator))

(defmethod ig/halt-key! :tunarr/curation
  [_ curator]
  (curation/stop! curator))

(defn- validate-channels! [channels]
  (doseq [[channel-key channel-cfg] channels]
    (let [missing (cond-> []
                    (nil? (::media/channel-id channel-cfg))          (conj ":id")
                    (nil? (::media/channel-fullname channel-cfg))    (conj ":name")
                    (nil? (::media/channel-description channel-cfg)) (conj ":description"))]
      (when (seq missing)
        (throw (ex-info (format "Channel %s is missing required config fields: %s. Set these under :channels > %s in your config."
                                (name channel-key)
                                (str/join ", " missing)
                                (name channel-key))
                        {:channel channel-key :missing missing}))))))

(defn- fetch-all-libraries
  "Fetch all libraries from Pseudovision and return a keyword-name → id map."
  [collection-config]
  (let [pv-libraries (pseudovision/list-all-libraries collection-config)]
    (into {} (map (fn [lib] [(keyword (:name lib)) (:id lib)]) pv-libraries))))

(defmethod ig/init-key :tunarr/config-sync [_ {:keys [channels collection-config catalog]}]
  (when (not channels)
    (throw (ex-info "missing required key: channels" {})))
  (validate-channels! channels)
  (log/info (format "syncing channels with config: %s"
                    (str/join "," (map name (keys channels)))))
  (catalog/update-channels! catalog channels)
  (log/info "fetching all libraries from Pseudovision")
  (let [libraries (fetch-all-libraries collection-config)]
    (log/info (format "syncing %d libraries: %s"
                      (count libraries)
                      (str/join "," (map name (keys libraries)))))
    (catalog/update-libraries! catalog libraries))
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

(defmethod ig/init-key :tunarr/pseudovision [_ config]
  (if (and config (:base-url config) (not= "" (:base-url config)))
    (do
      (log/info "Initializing Pseudovision backend" {:base-url (:base-url config)})
      (let [client (pseudovision/create config)
            validation (pseudovision/validate-config config)]
        (if (:valid? validation)
          (do
            (log/info "Pseudovision backend validated successfully"
                      {:version (:version validation)})
            client)
          (do
            (log/error "Pseudovision backend validation failed"
                       {:errors (:errors validation)})
            (throw (ex-info "Pseudovision validation failed" validation))))))
    (do (log/warn "Pseudovision backend not configured - skipping initialization"
                  {:config config})
        nil)))

(defmethod ig/halt-key! :tunarr/pseudovision [_ client]
  (log/info "Shutting down Pseudovision backend")
  nil)

;; Periodic scheduling is delegated to Kubernetes CronJobs that POST to
;; /api/scheduling/{daily,weekly,monthly,quarterly} (see deploy/k8s), so there
;; is no in-process scheduler component.

(defmethod ig/init-key :tunarr/bumpers [_ {:keys [tunabrain music-library-dir output-dir grout]}]
  (log/info "initialising bumper service")
  (bumpers/create-service {:tunabrain tunabrain
                           :music-library-dir music-library-dir
                           :output-dir output-dir
                           :grout grout}))

(defmethod ig/halt-key! :tunarr/bumpers [_ svc]
  (log/info "shutting down bumper service")
  (bumpers/close! svc))

(defmethod ig/init-key :tunarr/llm [_ {:keys [provider endpoint api-key model] :as config}]
  (log/info "initialising llm client" {:provider provider :model model})
  (llm/create! config))

(defmethod ig/halt-key! :tunarr/llm [_ client]
  (log/info "shutting down llm client")
  nil)

(defmethod ig/init-key :tunarr/http-server [_ {:keys [port scheduler media tts bumpers tunarr catalog logger job-runner collection tunabrain throttler llm curation-config pseudovision channels]}]
  (http/start! {:port port
                :job-runner job-runner
                :collection collection
                :catalog catalog
                :tunabrain tunabrain
                :throttler throttler
                :llm llm
                :pseudovision pseudovision
                :channels channels
                :curation-config curation-config
                :bumpers bumpers}))

(defmethod ig/halt-key! :tunarr/http-server [_ server]
  (http/stop! server))

(defn start
  ([system-config]
   (ig/init system-config))
  ([system-config opts]
   (ig/init system-config opts)))

(defn stop [system]
  (ig/halt! system))
