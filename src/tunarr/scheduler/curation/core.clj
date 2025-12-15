(ns tunarr.scheduler.curation.core
  (:require [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.jobs.throttler :as throttler]
            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.tunabrain :as tunabrain]
            [taoensso.timbre :as log]
            [clojure.spec.alpha :as s])
  (:import [java.time Instant]
           [java.time.temporal ChronoUnit]))

(defn process-callback
  [catalog {:keys [id name]} process]
  (fn [{:keys [error]}]
    (if error
      (log/error (format "failed to execute %s process on %s: %s"
                         process name (.getMessage error)))
      (catalog/update-process-timestamp! catalog id process))))

(defn process-timestamp
  [media target]
  (let [find-proc (partial some (fn [{:keys [process]}] (= process target)))]
    (some-> media
            :process-timestamps
            (find-proc)
            (get "last_run_at")
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

(defn days->millis
  [d]
  (* d 24 60 60 1000))

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
  (log/info (format "re-tagging media for library: %s" library))
  (let [threshold-date (days-ago threshold)]
    (doseq [{:keys [::media/name] :as media} (catalog/get-media-by-library-id catalog library)]
      (if (.after (process-timestamp media "tagging") threshold-date)
        (throttler/submit! throttler retag-media!
                           (process-callback catalog media "tagging")
                           [brain catalog media])
        (log/info (format "skipping tag generation on media: %s" name))))))

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
  (let [threshold-date (days-ago threshold)]
    (doseq [media (catalog/get-media-by-library-id catalog library)]
      (if (.after (process-timestamp media "categorize") threshold-date)
        (throttler/submit! throttler recategorize-media!
                           (process-callback catalog media "categorize")
                           [brain catalog media channels categories])
        (log/info "skipping tagline generation on media: %s" name)))))

(defrecord TunabrainCuratorBackend
    [brain catalog throttler config]
    ICuratorBackend
    
    (retag-library!
      [_ library]
      (retag-library-media! brain catalog library throttler
                            :threshold (days->millis (get-in config [:thresholds :retag]))))
    (generate-library-taglines!
      [_ library]
      (retag-library-media! brain catalog library throttler
                            :threshold (days->millis (get-in config [:thresholds :tagline]))))
    
    (recategorize-library!
      [_ library]
      (categorize-library-media! brain catalog library throttler
                                 :threshold (days->millis (get-in config [:thresholds :recategorize]))
                                 :channels (get config :channels)
                                 :categories (get config :categories))))

(defprotocol ICurator
  (start! [self])
  (stop! [self]))

(defrecord Curator
    [running? backend]
  ICurator
  (start! [_] (reset! running? true))
  (stop! [_] (reset! running? false)))

(defn create!
  [{:keys [tunabrain catalog throttler config]}]
  (let [backend (->TunabrainCuratorBackend tunabrain catalog throttler config)]
    (->Curator (atom false) backend)))

(defn start!
  [{:keys [running? backend] :as curator}
   ;; TODO: thresholds are for when to redo. Interval is how often to check.
   {:keys [interval]}
   libraries]
  (future
    (loop []
      (when @running?
        (doseq [library libraries]
          (retag-library! backend library)
          (generate-library-taglines! backend library)
          (recategorize-library! backend library)
          (log/info (format "processing library: %s" library)))
        (Thread/sleep (* 1000 60 interval)))
      (recur)))
  curator)

(defn stop!
  [{:keys [running?] :as curator}]
  (compare-and-set! running? true false)
  curator)
