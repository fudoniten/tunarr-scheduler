(ns tunarr.scheduler.media.pseudovision-collection
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.spec.alpha :as s]
            [taoensso.timbre :as log]
            [camel-snake-kebab.core :as csk]
            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.media.collection :as collection]
            [tunarr.scheduler.backends.pseudovision.client :as pv])
  (:import [java.time LocalDate]))

(defn- pv-request
  [method url opts]
  (try
    (let [response (http/request (merge {:method method
                                         :url url
                                         :accept :json
                                         :as :json
                                         :throw-exceptions false}
                                        opts))]
      (if (<= 200 (:status response) 299)
        (:body response)
        (do
          (log/error :status (:status response) :url url :body (:body response))
          (throw (ex-info (format "Pseudovision API error: %s" (:status response))
                  {:status (:status response)
                   :url url
                   :response (:body response)})))))
    (catch Exception e
      (log/error e :msg "HTTP request failed" :url url)
      (throw (ex-info "HTTP request failed" {:url url} e)))))

(defn- parse-pv-media-item
  [item library-id]
  (letfn [(default [d] (fn [o] (or o d)))]
    (-> item
        (assoc ::media/name (or (:title item) (:name item) :unknown))
        (assoc ::media/overview (:description item))
        (assoc ::media/genres (or (:genres item) []))
        (assoc ::media/community-rating (:community-rating item))
        (assoc ::media/critic-rating (:critic-rating item))
        (assoc ::media/rating (:content-rating item))
        (assoc ::media/id (str (:id item)))
        (assoc ::media/type (keyword (or (:kind item) (:type item) :movie)))
        (assoc ::media/production-year (:year item))
        (assoc ::media/subtitles (boolean (:has-subtitles item)))
        (assoc ::media/subtitles? (boolean (:has-subtitles item)))
        (assoc ::media/premiere (some-> (:premiere-date item) (LocalDate/parse)))
        (assoc ::media/taglines [])
        (assoc ::media/tags [])
        (assoc ::media/library-id library-id)
        (assoc ::media/kid-friendly? false)
        (cond->
          (= :episode (keyword (:kind item)))
          (assoc ::media/parent-id (str (:parent-id item))
                 ::media/season-number (:season-number item)
                 ::media/episode-number (:episode-number item))))))

(defn- ensure-episode-defaults
  [item]
  (if (= :episode (::media/type item))
    (cond-> item
      (nil? (::media/production-year item))
      (assoc ::media/production-year (.getYear (LocalDate/now)))
      (nil? (::media/subtitles item))
      (assoc ::media/subtitles false)
      (nil? (::media/subtitles? item))
      (assoc ::media/subtitles? false)
      (nil? (::media/taglines item))
      (assoc ::media/taglines [])
      (nil? (::media/tags item))
      (assoc ::media/tags [])
      (nil? (::media/genres item))
      (assoc ::media/genres [])
      (nil? (::media/premiere item))
      (assoc ::media/premiere (LocalDate/now)))
    item))

(defn- fetch-library-items-from-pv
  [config library-id]
  (log/info :msg "Fetching items from Pseudovision library" :library-id library-id)
  (try
    (let [items (pv/list-library-items config library-id {:attrs "id,title,description,year,kind,genres,parent-id,season-number,episode-number,remote-key"})]
      (log/info :msg "Got items from Pseudovision" :library-id library-id :count (count items))
      items)
    (catch Exception e
      (log/error e :msg "Failed to fetch library items" :library-id library-id)
      [])))

(defrecord PseudovisionMediaCollection [config]
  collection/MediaCollection

  (get-library-items [_ library]
    (let [libraries (pv/list-all-libraries config)
          ;; Normalize input library name for case-insensitive matching
          normalized-library (csk/->kebab-case (name library))
          library-match (some (fn [lib]
                                (when (= normalized-library (csk/->kebab-case (:name lib)))
                                  lib))
                              libraries)]
      (if-not library-match
        (throw (ex-info (str "media library not found: " (name library))
                {:library library
                 :normalized-library normalized-library
                 :available-libraries (mapv :name libraries)}))
        (let [items (fetch-library-items-from-pv config (:id library-match))
              parsed (map (fn [item]
                           (let [parsed (parse-pv-media-item item (:id library-match))
                                 defaulted (ensure-episode-defaults parsed)]
                             (if (s/invalid? (s/conform ::media/metadata defaulted))
                               (do (log/warn :msg "Skipping invalid media item" :item item)
                                   nil)
                               defaulted)))
                        items)]
          (doall (remove nil? parsed))))))

  (close! [_]
    (log/info :msg "Closing Pseudovision media collection")))

(s/def ::base-url string?)
(s/def ::verbose boolean?)

(s/def ::collection-config
  (s/keys :req-un [::base-url ::verbose]))

(defmethod collection/initialize-collection! :pseudovision
  [config]
  (let [checked-config (s/conform ::collection-config config)]
    (if (s/invalid? checked-config)
      (throw (ex-info "Invalid pseudovision collection configuration"
              {:error (s/explain-data ::collection-config config)}))
      (do
        (log/info :msg "Initializing Pseudovision media collection" :base-url (:base-url config))
        (->PseudovisionMediaCollection config)))))
