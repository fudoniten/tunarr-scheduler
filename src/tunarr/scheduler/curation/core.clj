(ns tunarr.scheduler.curation.core
  (:require [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.llm :as llm]
            [tunarr.scheduler.jobs.throttler :as throttler]
            [tunarr.scheduler.media :as media]

            [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [clojure.stacktrace :refer [print-stack-trace]])
  (:import [java.time.temporal ChronoUnit]))

(def system-prompt "You are a media content curator of JSON-formatted content. Respond in strict JSON format only.")

(defn capture-stack-trace
  [e]
  (with-out-str (print-stack-trace e)))

(defn parse-json [resp]
  (json/parse-string resp true))

(defn encode-json [data]
  (json/generate-string data))

(defn process-callback [catalog {:keys [id name]} process]
  (fn [{:keys [error]}]
    (if error
      (do (log/error (format "failed to execute %s process on %s: %s"
                             process name (.getMessage error)))
          (log/debug (capture-stack-trace error)))
      (catalog/update-process-timestamp! catalog id process))))

(defn tag-filter-prompt
  [media existing-tags]
  (str/join \newline
            (concat ["Given a media item, with description, and a set of tags, pick out and return any tags that apply to the media."
                     ""
                     "For example, tags for Jurassic Park might be: dinosaurs, action, thriller, jungle, scifi, computer_hacker"
                     ""
                     "Return the tags in strict JSON, as a list of strings, with each string being an individual tag."
                     ""
                     "Media data:"
                     ""]
                    [(encode-json media)]
                    ["" "Existing tags:" "" existing-tags])))

(defn tag-generate-prompt
  [media existing-tags]
  (str/join \newline
            (concat ["Given a media item, with description, and a set of applied tags, suggest any missing tags which should be applied."
                     ""
                     "For example, tags for Jurassic Park might be: dinosaurs, action, thriller, jungle, scifi, computer_hacker"
                     ""
                     "Tags should be lower-case, using python variable naming conventions (though some existing tags may not match that convention)."
                     ""
                     "Return the NEW tags in strict JSON, as a list of strings, with each string being an individual tag. For example:"
                     ""
                     "[\"adventure\", \"biology\", \"chaos_theory\"]"
                     ""
                     "Media data:"
                     ""]
                    [(encode-json media)]
                    ["" "Existing tags:" "" existing-tags])))

(defn tagline-prompt
  [media]
  (str/join \newline
            (concat ["Given a media item, with description, propose some taglines."
                     ""
                     "These should be short and sweet, and will be used in bumpers and other media. The taglines should give an idea what the media will be about."
                     ""
                     "Return a list of tagline strings, in strict JSON format."
                     ""
                     "An example might be, for Jurassic Park:"
                     ""
                     "[\"Life will find a way!\", \"Science opens the gates.\" \"65 Million Years in the Making!\"]"
                     ""
                     "Media data:"
                     ""]
                    [(encode-json media)])))

(defn categorize-prompt
  [media channels]
  (str/join \newline
            (concat ["Given a media item with description, and a list of television channels (with description), return the following attributes:"
                     ""
                     "* channels : A list of channel names on which this content could appear, from the provided list of channels."
                     "* kid_friendly : A boolean, true if this content is suitable for a mature 12-year-old kid, who's ready to be challenged a bit."
                     "* morning : A boolean, true if this is classic 'morning' content: morning cartoons, lightweight comedy, light documentaries, etc. Not M*A*S*H or serious dramas."
                     "* afternoon : A boolean, true if this is classing 'afternoon' content: drama, detective shows, documentaries, sitcoms. Not, for example, a horror film."
                     ""
                     "Return a list of channel names as strings, in strict JSON format."
                     ""
                     "An example might be, for Futurama:"
                     ""
                     (encode-json {:channels ["toontown" "galaxy"]
                                   :kid_friendly true
                                   :morning false
                                   :afternoon true})
                     ""
                     "Available channels:"
                     ""
                     (encode-json channels)
                     ""
                     "Media data:"
                     ""]
                    [(encode-json  media)])))

(defn json-prompt-with-retry
  [llm prompt & [retry err]]
  (if (and retry (> retry 3))
    (throw (ex-info (format "llm query failed: %s" (.getMessage err))
                    {:error err
                     :prompt prompt}))
    (try
      (let [full-prompt (if err
                          (str/join \newline
                                    (concat ["The following prompt produced this error:" ""
                                             (.getMessage err) ""
                                             "Please respond with strict JSON, correcting for the error." "" "Prompt:" ""
                                             prompt]))
                          prompt)]
        (->> full-prompt
             (llm/request! llm)
             (parse-json)))
      (catch JsonProcessingException e
        (json-prompt-with-retry llm prompt (+ (or retry 0) e))))))

(defprotocol ICuratorBackend
  (retag-library!             [self library])
  (generate-library-taglines! [self library])
  (recategorize-library!      [self library]))

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

(defn llm-retag-media!
  [llm catalog {:keys [::media/id ::media/name] :as media}
   & {:keys [batch-size]
      :or   {batch-size 50}}]
  (log/info (format "re-tagging media: %s" name))
  (let [tag-batches (map json/generate-string (partition batch-size (catalog/get-tags catalog)))
        current-tags (->> id
                          (catalog/get-media-tags catalog)
                          (json/generate-string))]
    (doseq [tag-set tag-batches]
      (when-let [tags (json-prompt-with-retry
                       llm
                       [{:role    "system"
                         :content system-prompt}
                        {:role    "user"
                         :content (tag-filter-prompt media tag-set)}])]
        (if (s/valid? (s/coll-of string?) tags)
          (do (log/info (format "Applying filtered tags to %s: %s" name tags))
              (catalog/add-media-tags! catalog id tags))
          (log/error (format "failed to parse tags for media %s: output: %s"
                             name tags))))
      (when-let [tags (json-prompt-with-retry
                       llm
                       [{:role    "system"
                         :content system-prompt}
                        {:role    "user"
                         :content (tag-generate-prompt media current-tags)}])]
        (if (s/valid? (s/coll-of string?) tags)
          (do (log/info (format "Applying new tags to %s: %s" (::media/name media) tags))
              (catalog/add-media-tags! catalog id tags))
          (log/error (format "failed to parse tags for media %s: output: %s"
                             (::media/name media) tags)))))))

(defn llm-retag-library!
  [llm catalog library throttler & {:keys [threshold]}]
  (log/info (format "re-tagging media for library: %s" library))
  (let [threshold-date (days-ago threshold)]
    (doseq [{:keys [::media/name] :as media} (catalog/get-media-by-library-id catalog library)]
      (if (.after (process-timestamp media "tagging") threshold-date)
        (throttler/submit! throttler llm-retag-media!
                           (process-callback catalog media "tagging")
                           [llm catalog media])
        (log/info (format "skipping tag generation on media: %s" name))))))

(defn llm-generate-media-taglines!
  [llm catalog media]
  (log/info (format "generating media taglines for media: %s" (::media/name media)))
  (when-let [taglines (json-prompt-with-retry
                       llm
                       [{:role "system"
                         :content system-prompt}
                        {:role "user"
                         :content (tagline-prompt media)}])]
    (if (s/valid? (s/coll-of string?) taglines)
      (do (log/info (format "Taglines for %s: %s" (::media/name media) taglines))
          (catalog/add-media-taglines! catalog (::media/id media) taglines))
      (log/error (format "Failed to generate taglines for media %s: output: %s"
                         (::media/name media)
                         taglines)))))

(defn llm-generate-library-taglines!
  [llm catalog library throttler & {:keys [threshold]}]
  (log/info (format "generating media taglines for library: %s" library))
  (let [threshold-date (days-ago threshold)]
    (doseq [media (catalog/get-media-by-library-id catalog library)]
      (if (.after (process-timestamp media "taglines") threshold-date)
        (throttler/submit! throttler llm-generate-media-taglines!
                           (process-callback catalog media "taglines")
                           [llm catalog media])
        (log/info "skipping tagline generation on media: %s" name)))))

(defn llm-recategorize-media!
  [llm catalog {:keys [::media/id ::media/name] :as media} channels]
  (log/info (format "recategorizing media: %s" name))
  (when-let [channels (json-prompt-with-retry
                       llm
                       [{:role "system"
                         :content system-prompt}
                        {:role "user"
                         :content (categorize-prompt media channels)}])]
    (if (s/valid? (s/coll-of string?) channels)
      (do (log/info (format "Channels for %s: %s" name channels))
          (catalog/add-media-channels! catalog id channels))
      (log/error (format "Failed to categorize media %s: output: %s"
                         name channels)))))

(defn llm-categorize-library-media!
  [llm catalog library throttler & {:keys [channels threshold]}]
  (log/info (format "recategorizing media for library: %s" library))
  (let [threshold-date (days-ago threshold)]
    (doseq [media (catalog/get-media-by-library-id catalog library)]
      (if (.after (process-timestamp media "categorize") threshold-date)
        (throttler/submit! throttler llm-recategorize-media!
                           (process-callback catalog media "categorize")
                           [llm catalog media channels])
        (log/info "skipping tagline generation on media: %s" name)))))

(defrecord LLMCuratorBackend
    [llm catalog throttler config]
  ICuratorBackend
  (retag-library!
    [_ library]
    (llm-retag-library! llm catalog library throttler
                        :threshold (get-in config [:thresholds :retag])))
  (generate-library-taglines!
    [_ library]
    (llm-generate-library-taglines! llm catalog library throttler
                                    :threshold (get-in config [:thresholds :tagline])))
  (recategorize-library!
    [_ library]
    (llm-categorize-library-media! llm catalog library throttler
                                   :threshold (get-in config [:thresholds :recategorize])
                                   :channels (get-in config [:channels]))))

(defprotocol ICurator
  (start! [self])
  (stop! [self]))

(defrecord Curator
    [running? backend]
  ICurator
  (start! [self] (reset! running? true))
  (stop! [self] (reset! running? false)))

(defn create!
  [{:keys [llm catalog throttler config]}]
  (let [backend (->LLMCuratorBackend llm catalog throttler config)]
    (->Curator (atom false) backend)))

(defn start!
  [{:keys [running? backend] :as curator}
   {:keys [tagging-delay taglines-delay categorization-delay]}
   libraries]
  (let [now (System/currentTimeMillis)
        min-time (fn [& args]
                   (let [t (System/currentTimeMillis)]
                     (apply min (map (fn [n] (- n t)) args))))]
    (future
      (loop [next-tag            (+ now tagging-delay)
             next-tagline        (+ now taglines-delay)
             next-categorization (+ now categorization-delay)]
        (when @running?
          (let [next-delay (min-time next-tag next-tagline next-categorization)]
            (when (> next-delay 0)
              (Thread/sleep next-delay))
            (let [t (System/currentTimeMillis)
                  do-tag? (>= t next-tag)
                  do-tagline? (>= t next-tagline)
                  do-recategorize? (>= t next-categorization)]
              (when do-tag?
                (doseq [library libraries]
                  (retag-library! backend library)))
              (when do-tagline?
                (doseq [library libraries]
                  (generate-library-taglines! backend library)))
              (when do-recategorize?
                (doseq [library libraries]
                  (recategorize-library! backend library)))
              (recur (if do-tag? (+ t tagging-delay) next-tag)
                     (if do-tagline? (+ t taglines-delay) next-tagline) 
                     (if do-recategorize? (+ t categorization-delay) next-categorization)))))))
    curator))

(defn stop!
  [{:keys [running?] :as curator}]
  (compare-and-set! running? true false)
  curator)
