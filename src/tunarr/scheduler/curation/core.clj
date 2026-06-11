(ns tunarr.scheduler.curation.core
  (:require [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.jobs.throttler :as throttler]
            [tunarr.scheduler.jobs.runner :as runner]
            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.tunabrain :as tunabrain]
            [tunarr.scheduler.curation.episode-tags :as episode-tags]
            [taoensso.timbre :as log]
            [clojure.spec.alpha :as s]
            [clojure.stacktrace :refer [print-stack-trace]])
  (:import [java.time Instant]
           [java.time.temporal ChronoUnit]
           [java.util.concurrent CountDownLatch TimeUnit]))

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
  "Return the last-run Instant for the given process from a collection of
   process timestamp maps, as returned by get-media-process-timestamps."
  [timestamps target]
  (some (fn [{:keys [::media/process-name ::media/last-run]}]
          (when (= process-name target)
            (if (instance? Instant last-run)
              last-run
              (some-> last-run .toInstant))))
        timestamps))

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

(defn overdue? [timestamps process threshold]
  (let [ts (process-timestamp timestamps process)]
    (or (nil? ts)
        (nil? threshold)
        (.isBefore ts threshold))))

;; ---------------------------------------------------------------------------
;; Job progress plumbing
;;
;; Library-wide curation submits one throttled task per media item; the
;; wrappers below tie those per-item tasks back to the owning job so the
;; /jobs API can report totals, completions, failures and the item currently
;; being processed. A latch keeps the job :running until the throttler has
;; drained every submitted item.
;; ---------------------------------------------------------------------------

(def default-completion-timeout-ms
  "How long a curation job waits for its throttled per-item work to finish
   before returning. On timeout the job completes but the remaining items
   keep running on the throttler."
  (* 24 60 60 1000))

(defn- progress-task
  "Wrap a throttled work fn so the owning job's current-item updates when the
   throttler actually starts processing the item."
  [f report-progress media]
  (fn [& args]
    (runner/item-started! report-progress {:id   (::media/id media)
                                           :name (::media/name media)})
    (apply f args)))

(defn- progress-callback
  "Wrap a throttler completion callback so it also updates the owning job's
   progress counters and releases the completion latch."
  [callback report-progress ^CountDownLatch latch]
  (fn [{:keys [error] :as outcome}]
    (try
      (callback outcome)
      (finally
        (if error
          (runner/item-failed! report-progress)
          (runner/item-completed! report-progress))
        (.countDown latch)))))

(defn- await-throttled-work!
  "Block until every submitted item has completed, or timeout-ms elapses.
   Returns true when all work finished in time."
  [^CountDownLatch latch description timeout-ms]
  (let [finished? (.await latch timeout-ms TimeUnit/MILLISECONDS)]
    (when-not finished?
      (log/warn (format "timed out waiting for %s; %d items still pending"
                        description (.getCount latch))))
    finished?))

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
  [brain catalog library throttler & {:keys [threshold force kind
                                             report-progress completion-timeout-ms]}]
  (log/info (format "re-tagging media for library: %s (force=%s, kind=%s)" (name library) (boolean force) (or (when kind (name kind)) "all")))
  (if (and (nil? threshold) (not force))
    (log/error "no value for retag threshold!")
    (let [report-progress (or report-progress (constantly nil))
          threshold-date  (when threshold (days-ago threshold))
          library-media   (if kind
                            (catalog/get-media-by-kind catalog library kind)
                            (catalog/get-media-by-library catalog library))
          due?            (fn [media]
                            (or force
                                (overdue? (catalog/get-media-process-timestamps catalog media)
                                          :process/tagging threshold-date)))
          {candidates true skipped false} (group-by due? library-media)
          latch           (CountDownLatch. (count candidates))]
      (log/info (format "processing tags for %d of %d media items from %s (%d skipped)"
                        (count candidates) (count library-media) (name library) (count skipped)))
      (doseq [media skipped]
        (log/info (format "skipping tag generation on media: %s" (::media/name media))))
      (runner/start-items! report-progress "tagging" (count candidates) (count skipped))
      (doseq [media candidates]
        (log/info (format "re-tagging media: %s" (::media/name media)))
        (throttler/submit! throttler
                           (progress-task retag-media! report-progress media)
                           (progress-callback (process-callback catalog media :process/tagging)
                                              report-progress latch)
                           [brain catalog media]))
      (await-throttled-work! latch (format "retag of library %s" (name library))
                             (or completion-timeout-ms default-completion-timeout-ms))
      {:library library
       :total   (count candidates)
       :skipped (count skipped)})))

(defn- series-episode-batches
  "For each series in the library, fetch its episodes and determine which are
   candidates for LLM special-flag tagging."
  [catalog library force]
  (let [library-media (catalog/get-media-by-library catalog library)
        series-items  (filter #(= :series (::media/type %)) library-media)]
    (mapv (fn [series]
            (let [episodes   (catalog/get-episodes-by-series catalog (::media/id series))
                  candidates (if force
                               episodes
                               (filterv episode-tags/episode-needs-special-tags? episodes))]
              (log/info (format "Episode tagging for series %s: %d total, %d candidates"
                                (::media/id series) (count episodes) (count candidates)))
              {:series series :episodes episodes :candidates candidates}))
          series-items)))

(defn- apply-deterministic-episode-tags!
  "Tier 1: apply deterministic (non-LLM) tags to every episode."
  [catalog episodes]
  (doseq [ep episodes]
    (let [auto-tags (episode-tags/auto-tag-episode ep)]
      (when (seq auto-tags)
        (log/info (format "Auto-tagging episode %s S%02dE%02d: %s"
                          (::media/name ep)
                          (::media/season-number ep)
                          (::media/episode-number ep)
                          auto-tags))
        (catalog/add-media-tags! catalog (::media/id ep) (vec auto-tags))))))

(defn tag-library-episodes!
  "Tag episodes for all series in a library. Tier 1 (deterministic) tags are
   applied to all episodes inline; Tier 2 (LLM) tagging is throttled and only
   runs on episodes that appear to need special tags."
  [brain catalog library throttler & {:keys [force report-progress completion-timeout-ms]}]
  (log/info (format "Tagging episodes for library: %s (force=%s)" (name library) (boolean force)))
  (let [report-progress (or report-progress (constantly nil))
        _               (report-progress {:phase "scanning"})
        batches         (series-episode-batches catalog library force)
        total-episodes  (transduce (map (comp count :episodes)) + 0 batches)
        candidates      (into [] (mapcat :candidates) batches)
        skipped         (- total-episodes (count candidates))
        latch           (CountDownLatch. (count candidates))]
    (log/info (format "Found %d series in %s for episode tagging (%d episodes, %d LLM candidates)"
                      (count batches) (name library) total-episodes (count candidates)))
    ;; Tier 1: deterministic tags for every episode
    (doseq [{:keys [episodes]} batches]
      (apply-deterministic-episode-tags! catalog episodes))
    ;; Tier 2: throttled LLM tagging for candidate episodes
    (runner/start-items! report-progress "episode-tagging" (count candidates) skipped)
    (doseq [ep candidates]
      (throttler/submit! throttler
                         (progress-task retag-episode-with-special-flags! report-progress ep)
                         (progress-callback (process-callback catalog ep :process/episode-tagging)
                                            report-progress latch)
                         [brain catalog ep]))
    (await-throttled-work! latch (format "episode tagging of library %s" (name library))
                           (or completion-timeout-ms default-completion-timeout-ms))
    {:library library
     :total   (count candidates)
     :skipped skipped}))

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
  [brain catalog library throttler & {:keys [threshold categories force
                                             report-progress completion-timeout-ms]}]
  (log/info (format "recategorizing media for library: %s (force=%s)" library (boolean force)))
  (let [report-progress (or report-progress (constantly nil))
        threshold-date  (when threshold (days-ago threshold))
        library-media   (catalog/get-media-by-library catalog library)
        due?            (fn [media]
                          (or force
                              (overdue? (catalog/get-media-process-timestamps catalog media)
                                        :process/categorize threshold-date)))
        {candidates true skipped false} (group-by due? library-media)
        latch           (CountDownLatch. (count candidates))]
    (log/info (format "categorizing %d of %d media items from %s (%d skipped)"
                      (count candidates) (count library-media) (name library) (count skipped)))
    (doseq [media skipped]
      (log/info (format "skipping categorization on media: %s" (::media/name media))))
    (runner/start-items! report-progress "categorizing" (count candidates) (count skipped))
    (doseq [media candidates]
      (throttler/submit! throttler
                         (progress-task recategorize-media! report-progress media)
                         (progress-callback (process-callback catalog media :process/categorize)
                                            report-progress latch)
                         [brain catalog media categories]))
    (await-throttled-work! latch (format "recategorize of library %s" (name library))
                           (or completion-timeout-ms default-completion-timeout-ms))
    {:library library
     :total   (count candidates)
     :skipped (count skipped)}))

(defrecord TunabrainCuratorBackend
    [brain catalog throttler config]
    ICuratorBackend

    (retag-library!
      [self library]
      (retag-library! self library {}))
    (retag-library!
      [_ library {:keys [force kind report-progress]}]
      (retag-library-media! brain catalog library throttler
                            :threshold (get-in config [:thresholds :retag])
                            :force force
                            :kind kind
                            :report-progress report-progress))

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
      [_ library {:keys [force report-progress]}]
      (categorize-library-media! brain catalog library throttler
                                 :threshold (get-in config [:thresholds :recategorize])
                                 :categories (get config :categories)
                                 :force force
                                 :report-progress report-progress))

    (retag-library-episodes!
      [self library]
      (retag-library-episodes! self library {}))
    (retag-library-episodes!
      [_ library {:keys [force report-progress]}]
      (tag-library-episodes! brain catalog library throttler
                             :force force
                             :report-progress report-progress)))

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
