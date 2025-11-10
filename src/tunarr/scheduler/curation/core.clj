(ns tunarr.scheduler.curation.core
  (:require [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.llm :as llm]
            [tunarr.scheduler.media :as media]

            [clojure.string :as str]
            [clojure.spec.alpha :as s]
            
            [cheshire.core :as json]

            [camel-snake-kebab.core :refer [->snake_case_keyword]]

            [taoensso.timbre :as log])
  (:import [com.fasterxml.jackson.core JsonProcessingException]))

(defn tag-prompt
  [media existing-tags]
  (str/join \newline
            (concat ["Given a media item, with description, assign tags to the media for use in scheduling and categorizing the media."
                     ""
                     "Tags should be lower-case, using python variable naming conventions (though some existing tags may not match that convention)."
                     ""
                     "For example, tags for Jurassic Park might be: dinosaurs, action, thriller, jungle, scifi, computer_hacker"
                     ""
                     "If the existing tags are sufficient, you do not need to add more."
                     ""
                     "Return the tags in strict JSON, as a list of strings, with each string being an individual tag."
                     ""
                     "Reuse existing tags where available, but feel free to create new ones where they will be useful."
                     ""
                     "Media data:"
                     ""]
                      [(json/generate-string media)]
                      ["" "Existing tags:" "" existing-tags])))

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
                      [(json/generate-string media)]
                      ["" "Existing tags:" "" existing-tags])))

(defn tag-generate-prompt
  [media existing-tags]
  (str/join \newline
            (concat ["Given a media item, with description, and a set of applied tags, suggest any tags which should be applied."
                     ""
                     "For example, tags for Jurassic Park might be: dinosaurs, action, thriller, jungle, scifi, computer_hacker"
                     ""
                     "Tags should be lower-case, using python variable naming conventions (though some existing tags may not match that convention)."
                     ""
                     "Return the NEW tags in strict JSON, as a list of strings, with each string being an individual tag."
                     ""
                     "Media data:"
                     ""]
                      [(json/generate-string media)]
                      ["" "Existing tags:" "" existing-tags])))

(def system-prompt "You are a media content curator of JSON-formatted content. Respond in strict JSON format only.")

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
                     "[\"Life will find a way!\", \"Science opens the gates.\" \"65 Million Years in the Making\"]"
                     ""
                     "Media data:"
                     ""]
                    [(json/generate-string media)])))

(defn parse-json [resp]
  (json/parse-string resp true))

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

(defprotocol ICurator
  (retag-library!             [self library])
  (generate-library-taglines! [self library])
  (recategorize-library!      [self library]))

(defn llm-retag-library!
  [llm catalog library]
  (log/info (format "re-tagging media for library: %s" library))
  (let [tag-list (json/generate-string (map str (catalog/get-tags catalog)))]
    (doseq [media (catalog/get-media-by-library-id catalog library)]
      (log/info (format "tagging media: %s" (::media/name media)))
      (when-let [tags (json-prompt-with-retry
                       llm
                       [{:role    "system"
                         :content system-prompt}
                        {:role    "user"
                         :content (tag-prompt media tag-list)}])]
        (if (s/valid? (s/coll-of string?) tags)
          (do (log/info (format "Tags for %s: %s" (::media/name media) (tags)))
              (catalog/add-media-tags catalog (::media/id media) (map ->snake_case_keyword tags)))
          (log/error (format "failed to generate tags for media %s: output: %s"
                             (::media/name media)
                             tags)))))))

(defn llm-retag-media!
  [llm catalog media]
  (log/info (format "re-tagging media: %s" (::media/name media)))
  (let [media-id (::media/id media)
        tag-batches (map json/generate-string (partition 50 (catalog/get-tags catalog)))
        current-tags (->> media-id
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
          (do (log/info (format "Applying filtered tags to %s: %s" (::media/name media) tags))
              (catalog/add-media-tags catalog media-id tags))
          (log/error (format "failed to parse tags for media %s: output: %s"
                             (::media/name media) tags))))
      (when-let [tags (json-prompt-with-retry
                       llm
                       [{:role    "system"
                         :content system-prompt}
                        {:role    "user"
                         :content (tag-generate-prompt media current-tags)}])]
        (if (s/valid? (s/coll-of string?) tags)
          (do (log/info (format "Applying new tags to %s: %s" (::media/name media) tags))
              (catalog/add-media-tags catalog media-id tags))
          (log/error (format "failed to parse tags for media %s: output: %s"
                             (::media/name media) tags)))))))

(defn llm-generate-library-taglines!
  [llm catalog library]
  (log/info (format "generating media taglines for library: %s" library))
  (doseq [media (catalog/get-media-by-library-id catalog library)]
    (log/info (format "generating taglines for media: %s" (::media/name media)))
    (when-let [taglines (json-prompt-with-retry
                         llm
                         [{:role "system"
                           :content system-prompt}
                          {:role "user"
                           :content (tagline-prompt media)}])]
      (if (s/valid? (s/coll-of string?) taglines)
        (do (log/info (format "Taglines for %s: %s" (::media/name media) taglines))
            (catalog/add-media-taglines catalog (::media/id media) taglines))
        (log/error (format "Failed to generate taglines for media %s: output: %s"
                           (::media/name media)
                           taglines))))))

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
          (catalog/add-media-taglines catalog (::media/id media) taglines))
      (log/error (format "Failed to generate taglines for media %s: output: %s"
                         (::media/name media)
                         taglines)))))

(defn llm-categorize-library-media!
  [llm catalog library]
  (log/info (format "recategorizing media for library: %s" library))
  (doseq [media (catalog/get-media-by-library-id catalog library)]
    (log/info (format "recategorizing media: %s" (::media/name media)))))


(defrecord LLMCurator [llm catalog]
  (retag-library! [_ library] (llm-retag-library! llm catalog library))
  (generate-library-taglines! [_ library] (llm-generate-library-taglines! llm catalog library))
  (recategorize-library! [_ library] (throw (ex-info "not implemented: recategorize-library!" {}))))
