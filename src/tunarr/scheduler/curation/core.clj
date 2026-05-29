(ns tunarr.scheduler.curation.core
  (:require [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.jobs.throttler :as throttler]
            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.tunabrain :as tunabrain]
            [tunarr.scheduler.curation.episode-tags :as episode-tags]
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
  (retag-library! [self library] [self library opts]
    "Regenerate and apply tags for the supplied library.")
  (generate-library-taglines! [self library] [self library opts]
    "Generate taglines for media in the supplied library.")
  (recategorize-library! [self library] [self library opts]
    "Update channel mapping metadata for the supplied library.")
  (retag-library-episodes! [self library] [self library opts]
    "Selectively tag episodes that are candidates for special tags.
     Tier 1 (deterministic) runs on all episodes, Tier 2 (LLM) only
     on episodes that appear to need special tags."))

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

(defn retag-episode-with-special-flags!
  "Tag an episode using the lightweight special flags endpoint.
   Calls tunabrain's /tags/episode-special-flag with constrained vocabulary."
  [brain catalog {:keys [::media/id ::media/name ::media/parent-id] :as media}]
  (log/info (format "flagging episode with special tags: %s" name))
  (when-let [response (tunabrain/request-episode-special-flags! brain media
                                                                :parent-title (some-> parent-id
                                                                                       (catalog/get-media-by-id catalog)
                                                                                       (::media/name))
                                                                :existing-flags (catalog/get-media-tags catalog id))]
    (when-let [flags (:flags response)]
      (when (s/valid? ::media/tags flags)
        (log/info (format "Applying special flags to episode %s: %s" name flags))
        (catalog/add-media-tags! catalog id (vec flags))))))

(defn retag-library-media!
  [brain catalog library throttler & {:keys [threshold force kind]}]
  (log/info (format "re-tagging media for library: %s (force=%s, kind=%s)" (name library) (boolean force) (or (when kind (name kind)) "all")))
  (if (and (nil? threshold) (not force))
    (log/error "no value for retag threshold!")
    (let [threshold-date (when threshold (days-ago threshold))
          library-media  (if kind
                          (catalog/get-media-by-kind catalog library kind)
                          (catalog/get-media-by-library catalog library))]
      (log/info (format "processing tags for %s media items from %s"
                        (count library-media) (name library)))
      (doseq [media library-media]
        (if (or force
                (overdue? (catalog/get-media-process-timestamps catalog media)
                          :process/tagging threshold-date))
          (do (log/info (format "re-tagging media: %s" (::media/name media)))
              (throttler/submit! throttler retag-media!
                                 (process-callback catalog media :process/tagging)
                                 [brain catalog media]))
          (log/info (format "skipping tag generation on media: %s" (::media/name media))))))))

(defn retag-series-episodes!
  "Tag episodes for a single series. Tier 1 (deterministic) tags are applied to
   all episodes. Tier 2 (LLM) tagging is submitted for episodes that appear to
   need special tags."
  [brain catalog series-id throttler & {:keys [force]}]
  (let [episodes (catalog/get-episodes-by-series catalog series-id)]
    (when (seq episodes)
      (let [candidates (if force
                         episodes
                         (filter episode-tags/episode-needs-special-tags? episodes))]
        (log/info (format "Episode tagging for series %s: %d total, %d candidates"
                          series-id (count episodes) (count candidates)))
        ;; Tier 1: Apply deterministic tags to all episodes
        (doseq [ep episodes]
          (let [auto-tags (episode-tags/auto-tag-episode ep)]
            (when (seq auto-tags)
              (log/info (format "Auto-tagging episode %s S%02dE%02d: %s"
                                (::media/name ep)
                                (::media/season-number ep)
                                (::media/episode-number ep)
                                auto-tags))
              (catalog/add-media-tags! catalog (::media/id ep) (vec auto-tags)))))
        ;; Tier 2: Send candidates to LLM for refined tagging via special flags endpoint
        (doseq [ep candidates]
          (throttler/submit! throttler retag-episode-with-special-flags!
                             (process-callback catalog ep :process/episode-tagging)
                             [brain catalog ep]))))))

(defn retag-library-episodes!
  "Tag episodes for all series in a library."
  [brain catalog library throttler & {:keys [threshold force]}]
  (log/info (format "Tagging episodes for library: %s (force=%s)" (name library) (boolean force)))
  (let [library-media (catalog/get-media-by-library catalog library)
        series-items  (filter #(= :series (::media/type %)) library-media)]
    (log/info (format "Found %d series in %s for episode tagging"
                      (count series-items) (name library)))
    (doseq [series series-items]
      (retag-series-episodes! brain catalog (::media/id series) throttler
                              :force force))))

(s/def ::categorization
  (s/map-of ::media/category-name
            (s/coll-of (s/keys :req [::media/category-value
                                     ::media/rationale]))))

(defn recategorize-media!
  [brain catalog {:keys [::media/id ::media/name] :as media} categories]
  (log/info (format "recategorizing media: %s" name))
  (when-let [response (tunabrain/request-categorization! brain media
                                                         :categories categories)]
    (let [{:keys [dimensions]} response]
      (when (s/valid? ::categorization dimensions)
        (doseq [[category selections] dimensions]
          (catalog/set-media-category-values! catalog id category selections))))))

(defn categorize-library-media!
  [brain catalog library throttler & {:keys [threshold categories force]}]
  (log/info (format "recategorizing media for library: %s (force=%s)" library (boolean force)))
  (let [threshold-date (when threshold (days-ago threshold))
        library-media  (catalog/get-media-by-library catalog library)]
    (log/info (format "processing tags for %s media items from %s"
                      (count library-media) (name library)))
    (doseq [media library-media]
      (if (or force
              (overdue? (catalog/get-media-process-timestamps catalog media)
                        :process/categorize threshold-date))
        (throttler/submit! throttler recategorize-media!
                           (process-callback catalog media :process/categorize)
                           [brain catalog media categories])
        (log/info "skipping tagline generation on media: %s" (::media/name media))))))

(defrecord TunabrainCuratorBackend
    [brain catalog throttler config]
    ICuratorBackend

    (retag-library!
      [self library]
      (retag-library! self library {}))
    (retag-library!
      [_ library {:keys [force kind]}]
      (retag-library-media! brain catalog library throttler
                            :threshold (get-in config [:thresholds :retag])
                            :force force
                            :kind kind))

    (generate-library-taglines!
      [self library]
      (generate-library-taglines! self library {}))
    (generate-library-taglines!
      [_ _ _opts]
      (throw (ex-info "generate-library-taglines! not implemented" {})))

    (recategorize-library!
      [self library]
      (recategorize-library! self library {}))
    (recategorize-library!
      [_ library {:keys [force]}]
      (categorize-library-media! brain catalog library throttler
                                 :threshold (get-in config [:thresholds :recategorize])
                                 :categories (get config :categories)
                                 :force force))

    (retag-library-episodes!
      [self library]
      (retag-library-episodes! self library {}))
    (retag-library-episodes!
      [_ library {:keys [force]}]
      (retag-library-episodes! brain catalog library throttler
                               :threshold (get-in config [:thresholds :retag-episodes])
                               :force force)))

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
            (recategorize-library! backend library)
            (log/info (format "tagging episodes for library: %s" (name library)))
            (retag-library-episodes! backend library))
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
