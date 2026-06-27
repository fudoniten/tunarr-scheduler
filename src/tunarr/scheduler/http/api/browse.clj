(ns tunarr.scheduler.http.api.browse
  "HTTP handlers for browsing tags, channels, and genres."
  (:require [taoensso.timbre :as log]
            [clojure.walk :as walk]
            [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.http.util :as util])
  (:import [java.time LocalDate Instant]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- serialize-time-fields [data]
  (walk/postwalk
   (fn [v]
     (cond
       (instance? LocalDate v) (str v)
       (instance? Instant v)   (str v)
       :else v))
   data))

;; ---------------------------------------------------------------------------
;; Tag handlers
;; ---------------------------------------------------------------------------

(defn list-tags-handler
  "List all tags with usage counts and example titles."
  [{:keys [catalog]}]
  (fn [_]
    (try
      (let [samples (catalog/get-tag-samples catalog)]
        {:status 200
         :body   {:tags (mapv (fn [{:keys [tag usage_count example_titles]}]
                                {:tag           tag
                                 :usage-count   usage_count
                                 :example-titles (vec example_titles)})
                              samples)}})
      (catch Exception e
        (log/error e "Error listing tags")
        {:status 500 :body {:error (util/error-message e)}}))))

(defn get-media-by-tag-handler
  "List all media items that have a given tag."
  [{:keys [catalog]}]
  (fn [req]
    (try
      (let [tag  (get-in req [:parameters :path :tag])
            media (catalog/get-media-by-tag catalog tag)]
        {:status 200 :body {:media (mapv serialize-time-fields media)}})
      (catch Exception e
        (log/error e "Error fetching media by tag")
        {:status 500 :body {:error (util/error-message e)}}))))

;; ---------------------------------------------------------------------------
;; Channel handlers
;; ---------------------------------------------------------------------------

(defn ^:deprecated list-channels-handler
  "DEPRECATED: Hardcoded channel list. Channels are dimensions now.
    Use list-tags-handler or get-media-by-tag-handler with channel:NAME tag.
    See DIMENSION_CLEANUP.md for removal timeline."
  [{:keys [catalog]}]
  (fn [_]
    (try
      {:status 200 :body {:channels (vec (catalog/get-channels catalog))}}
      (catch Exception e
        (log/error e "Error listing channels")
        {:status 500 :body {:error (util/error-message e)}}))))

(defn ^:deprecated get-media-by-channel-handler
  "DEPRECATED: Hardcoded channel filter. Channels are dimensions now.
    Use get-media-by-tag-handler with the channel:NAME tag.
    See DIMENSION_CLEANUP.md for removal timeline."
  [{:keys [catalog]}]
  (fn [req]
    (try
      (let [channel (get-in req [:parameters :path :channel-name])
            media   (catalog/get-media-by-channel catalog channel)]
        {:status 200 :body {:media (mapv serialize-time-fields media)}})
      (catch Exception e
        (log/error e "Error fetching media by channel")
        {:status 500 :body {:error (util/error-message e)}}))))

;; ---------------------------------------------------------------------------
;; Genre handlers
;; ---------------------------------------------------------------------------

(defn ^:deprecated list-genres-handler
  "DEPRECATED: Hardcoded genre list. Genres are dimensions now.
    Use list-tags-handler or get-media-by-tag-handler with genre:NAME tag.
    See DIMENSION_CLEANUP.md for removal timeline."
  [{:keys [catalog]}]
  (fn [_]
    (try
      (let [genres (catalog/get-genres catalog)]
        {:status 200 :body {:genres (mapv name genres)}})
      (catch Exception e
        (log/error e "Error listing genres")
        {:status 500 :body {:error (util/error-message e)}}))))

(defn ^:deprecated get-media-by-genre-handler
  "DEPRECATED: Hardcoded genre filter. Genres are dimensions now.
    Use get-media-by-tag-handler with the genre:NAME tag.
    See DIMENSION_CLEANUP.md for removal timeline."
  [{:keys [catalog]}]
  (fn [req]
    (try
      (let [genre (get-in req [:parameters :path :genre])
            media (catalog/get-media-by-genre catalog genre)]
        {:status 200 :body {:media (mapv serialize-time-fields media)}})
      (catch Exception e
        (log/error e "Error fetching media by genre")
        {:status 500 :body {:error (util/error-message e)}}))))

;; ---------------------------------------------------------------------------
;; Dimension handlers
;; ---------------------------------------------------------------------------

(defn list-dimensions-handler
  "List all dimensions with their value counts."
  [{:keys [catalog]}]
  (fn [_]
    (try
      (let [dims (catalog/get-all-dimensions catalog)]
        {:status 200 :body {:dimensions (mapv (fn [{:keys [name value-count]}]
                                               {:name (clojure.core/name name)
                                                :value-count value-count})
                                             dims)}})
      (catch Exception e
        (log/error e "Error listing dimensions")
        {:status 500 :body {:error (util/error-message e)}}))))

(defn get-dimension-values-handler
  "List all values for a given dimension with usage counts."
  [{:keys [catalog]}]
  (fn [req]
    (try
      (let [dimension (get-in req [:parameters :path :dimension])
            values    (catalog/get-dimension-values catalog (keyword dimension))]
        {:status 200 :body {:values (mapv (fn [{:keys [value usage-count]}]
                                           {:value (clojure.core/name value)
                                            :usage-count usage-count})
                                         values)}})
      (catch Exception e
        (log/error e "Error fetching dimension values")
        {:status 500 :body {:error (util/error-message e)}}))))

(defn get-media-categories-handler
  "Get all dimension categories for a specific media item."
  [{:keys [catalog]}]
  (fn [req]
    (try
      (let [media-id (get-in req [:parameters :path :media-id])
            categories (catalog/get-media-categories catalog media-id)]
        {:status 200 :body {:categories (into {}
                                            (map (fn [[k v]]
                                                   [(clojure.core/name k) (mapv clojure.core/name v)]))
                                            categories)}})
      (catch Exception e
        (log/error e "Error fetching media categories")
        {:status 500 :body {:error (util/error-message e)}}))))
