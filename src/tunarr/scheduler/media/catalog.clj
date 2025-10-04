(ns tunarr.scheduler.media.catalog
  "Media catalog integration with Jellyfin or Tunarr."
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [taoensso.timbre :as log]
            [tunarr.scheduler.llm :as llm]))

(defn- jellyfin-request [config path]
  (let [base (:base-url config)
        url (str base path)
        opts {:headers {"X-Emby-Token" (:api-key config)}}]
    (log/info "Fetching Jellyfin resources" {:url url})
    (-> (http/get url opts)
        :body
        (json/parse-string true))))

(defn fetch-library
  "Fetch media metadata from Jellyfin. In the skeleton this returns an empty vector."
  [config]
  (if (:base-url config)
    (do (log/info "Fetching media libraries from Jellyfin" {:libraries (:library-ids config)})
        [])
    []))

(defn create-catalog [config]
  (log/info "Initialising media catalog" {:source (if (:base-url config) :jellyfin :tunarr)})
  {:config config
   :state (atom {})})

(defn close! [_]
  (log/info "Shutting down media catalog"))

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

(defn tag-media!
  "Fetch media and classify with the LLM."
  [{:keys [state config]} llm persistence]
  (let [media (fetch-library config)
        tagged (map #(merge % (llm/classify-media! llm %)) media)]
    (persist-media! persistence (vec tagged))))
