(ns tunarr.scheduler.media.catalog
  "Media catalog integration with Jellyfin or Tunarr."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [taoensso.timbre :as log]))

(defprotocol Catalog
  (add-media [catalog media-id media])
  (get-media [catalog])
  (get-media-by-id [catalog media-id])
  (set-media-tags [catalog media-id tags])
  (set-media-channels [catalog media-id channels])
  (get-media-by-channel [catalog channel])
  (get-media-by-tag [catalog tag]))

(defn create-catalog [config]
  (log/info "Initialising media catalog" {:source (if (:base-url config) :jellyfin :tunarr)})
  {:config config
   :state (atom {})})

(defn create-persistence [config]
  (log/info "Initialising persistence layer" {:type (:type config)})
  (case (:type config)
    :filesystem {:type :filesystem :path (:path config)}
    :memory {:type :memory :state (atom {})}
    {:type :memory :state (atom {})}))

(defn close-persistence! [_]
  (log/info "Closing persistence layer"))

(defn persist-media!
  "Persist tagged media. Placeholder persists in memory or writes edn file."
  [{:keys [type path state]} media]
  (case type
    :filesystem (spit path (pr-str media))
    :memory (reset! state media)
    nil)
  media)

(defn list-tagged-media
  "Retrieve cached tagged media."
  [{:keys [type path state]}]
  (case type
    :filesystem (when (.exists (io/file path))
                  (with-open [r (io/reader path)]
                    (edn/read r)))
    :memory @state
    nil))

#_(defn tag-media!
  "Fetch media and classify with the LLM."
  [{:keys [state config]} llm persistence]
  (let [media (fetch-library config)
        tagged (map #(merge % (llm/classify-media! llm %)) media)]
    (persist-media! persistence (vec tagged))))
