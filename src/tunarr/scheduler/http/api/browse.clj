(ns tunarr.scheduler.http.api.browse
  "HTTP handlers for browsing tags, channels, and genres."
  (:require [taoensso.timbre :as log]
            [clojure.walk :as walk]
            [tunarr.scheduler.media :as media]
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

(defn get-channel-descriptions-handler
  "Return each configured channel's value + description, derived from the
  `:channels` config map in the handler context (the same source `/api/catalog/channels`
  uses). The response shape matches the values list shape
  (`:values` of maps with `:value`) so a caller can swap them
  one-for-one when an extra `:description` is wanted.

  Channels without a description are still returned with an empty
  description string — Grout's vocabulary guard tolerates either, and
  omitting channels would silently reduce the controlled vocabulary
  in a way that's harder to debug than a blank description.

  Channels without a known description are intentionally not dropped:
  the controlled vocabulary should stay the same set of values
  whether or not descriptions are populated, so a missing description
  is a content problem, not a structural one."
  [{:keys [channels]}]
  (fn [_]
    (try
      (let [rows (->> (or channels {})
                      (map (fn [[ch-name cfg]]
                             {:value       (name ch-name)
                              :description (or (::media/channel-description cfg)
                                               "")})))]
        {:status 200
         :body {:values (vec (sort-by :value rows))}})
      (catch Exception e
        (log/error e "Error fetching channel descriptions")
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

(defn get-media-by-dimension-value-handler
  "List all media items that have a given dimension category value."
  [{:keys [catalog]}]
  (fn [req]
    (let [dimension (get-in req [:parameters :path :dimension])
          value     (get-in req [:parameters :path :value])]
      (try
        (let [media (catalog/get-media-by-category-value catalog 
                                                         (keyword dimension)
                                                         (keyword value))]
          {:status 200 :body {:media (mapv serialize-time-fields media)}})
        (catch Exception e
          (log/error e "Error fetching media by dimension value"
                    {:dimension dimension :value value})
          {:status 500 :body {:error (util/error-message e)}})))))
