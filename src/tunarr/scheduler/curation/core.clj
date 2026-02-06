(ns tunarr.scheduler.curation.core
  (:require [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.jobs.throttler :as throttler]
            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.tunabrain :as tunabrain]
            [taoensso.timbre :as log]
            [clojure.spec.alpha :as s]
            [clojure.stacktrace :refer [print-stack-trace]])
  (:import [java.time Instant]
           [java.time.temporal ChronoUnit]))

(defn process-callback
  [catalog {:keys [::media/id ::media/name]} process]
  (fn [{:keys [error]}]
    (if error
      (do
        (log/error (format "failed to execute %s process on %s: %s"
                           process name (.getMessage error)))
        (log/debug (with-out-str (print-stack-trace error))))
      (catalog/update-process-timestamp! catalog id process))))

(defn process-timestamp
  [media target]
  (let [find-proc (partial some
                           (fn [{:keys [::media/process-name]}]
                             (= process-name target)))]
    (some-> media
            ::media/process-timestamps
            (find-proc)
            (get ::media/last-run)
            (.toInstant))))

(defn days-ago
  [days]
  (.minus (Instant/now) days ChronoUnit/DAYS))

(defprotocol ICuratorBackend
  (retag-library! [self library]
    "Regenerate and apply tags for the supplied library.")
  (generate-library-taglines! [self library]
    "Generate taglines for media in the supplied library.")
  (recategorize-library! [self library]
    "Update channel mapping metadata for the supplied library."))

(defn overdue? [media process threshold]
  (let [ts (process-timestamp media process)]
    (if (nil? ts)
      true
      (.isBefore ts threshold))))

(defn retag-media!
  [brain catalog {:keys [::media/id ::media/name] :as media}]
  (log/info (format "re-tagging media: %s" name))
  (when-let [response (tunabrain/request-tags! brain media
                                               :catalog-tags (catalog/get-tags catalog))]
    (when-let [tags (or (:tags response) (:filtered-tags response))]
      (when (s/valid? ::media/tags tags)
        (log/info (format "Applying tags to %s: %s" name tags))
        (catalog/set-media-tags! catalog id tags)))
    (when-let [taglines (:taglines response)]
      (when (s/valid? (s/coll-of string?) taglines)
        (log/info (format "Taglines for %s: %s" name taglines))
        (catalog/add-media-taglines! catalog id taglines)))))

(defn retag-library-media!
  [brain catalog library throttler & {:keys [threshold]}]
  (log/info (format "re-tagging media for library: %s" (name library)))
  (if (nil? threshold)
    (log/error "no value for retag threshold!")
    (let [threshold-date (days-ago threshold)
          library-media  (catalog/get-media-by-library catalog library)]
      (log/info (format "processing tags for %s media items from %s"
                        (count library-media) (name library)))
      (doseq [media library-media]
        (if (overdue? (catalog/get-media-process-timestamps catalog media)
                      :process/tagging threshold-date)
          (do (log/info (format "re-tagging media: %s" (::media/name media)))
              (throttler/submit! throttler retag-media!
                                 (process-callback catalog media :process/tagging)
                                 [brain catalog media]))
          (log/info (format "skipping tag generation on media: %s" (::media/name media))))))))

(s/def ::channel-mapping
  (s/keys :req [::media/channel-name ::media/rationale]))
(s/def ::channel-mappings (s/coll-of ::channel-mapping))

(s/def ::categorization
  (s/map-of ::media/category-name
            (s/coll-of (s/keys :req [::media/category-value
                                     ::media/rationale]))))

(defn recategorize-media!
  [brain catalog {:keys [::media/id ::media/name] :as media} channels categories]
  (log/info (format "recategorizing media: %s" name))
  (when-let [response (tunabrain/request-categorization! brain media
                                                         :categories categories
                                                         :channels   channels)]
    (let [{:keys [dimensions mappings]} response]
      (when (s/valid? ::channel-mappings mappings)
        (let [channel-names (map ::media/channel-name mappings)]
          (log/info (format "Channels for %s: %s" name channel-names))
          (catalog/add-media-channels! catalog id channel-names)))
      (when (s/valid? ::categorization dimensions)
        (doseq [[category values] dimensions]
          (catalog/set-media-category-values! catalog id category values))))))

(defn categorize-library-media!
  [brain catalog library throttler & {:keys [channels threshold categories]}]
  (log/info (format "recategorizing media for library: %s" library))
  (let [threshold-date (days-ago threshold)
        library-media  (catalog/get-media-by-library catalog library)]
    (log/info (format "processing tags for %s media items from %s"
                      (count library-media) (name library)))
    (doseq [media library-media]
      (if (overdue? (catalog/get-media-process-timestamps catalog media)
                    :process/categorize threshold-date)
        (throttler/submit! throttler recategorize-media!
                           (process-callback catalog media :process/categorize)
                           [brain catalog media channels categories])
        (log/info "skipping tagline generation on media: %s" (::media/name media))))))

(defrecord TunabrainCuratorBackend
    [brain catalog throttler config]
    ICuratorBackend
    
    (retag-library!
      [_ library]
      (retag-library-media! brain catalog library throttler
                            :threshold (get-in config [:thresholds :retag])))
    (generate-library-taglines!
      [_ _]
      (throw (ex-info "generate-library-taglines! not implemented" {})))
    
    (recategorize-library!
      [_ library]
      (categorize-library-media! brain catalog library throttler
                                 :threshold (get-in config [:thresholds :recategorize])
                                 :channels (get config :channels)
                                 :categories (get config :categories))))

(defprotocol ICurator
  (start! [self libraries])
  (stop! [self]))

(defn start-curation!
  ;; TODO: thresholds are for when to redo. Interval is how often to check.
  [running? backend {:keys [interval]} libraries]
  (reset! running? true)
  (future
    (log/info "beginning curation step")
    (loop []
      (if @running?
        (try
          (doseq [library libraries]
            (log/info (format "retagging library: %s" (name library)))
            (retag-library! backend library)
            ;; TODO: Implement tagline generation when ready
            (log/info (format "recategorizing library: %s" (name library)))
            (recategorize-library! backend library))
          (catch Throwable t
            (log/error (with-out-str (print-stack-trace t)))))
        (log/info "skipping curation, not running"))
      (Thread/sleep (* 1000 60 interval))
      (recur))))

(defrecord Curator
    [running? backend config]
    ICurator
    (start! [self libraries]
      (start-curation! running? backend config libraries)
      self)
    (stop! [self]
      (compare-and-set! running? true false)
      self))

(defn create!
  [{:keys [tunabrain catalog throttler config]}]
  (let [backend (->TunabrainCuratorBackend tunabrain catalog throttler config)]
    (->Curator (atom false) backend config)))
