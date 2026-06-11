(ns tunarr.scheduler.http.api.browse
  "HTTP handlers for browsing tags, channels, and genres."
  (:require [taoensso.timbre :as log]
            [clojure.walk :as walk]
            [tunarr.scheduler.media.catalog :as catalog])
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
        {:status 500 :body {:error (.getMessage e)}}))))

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
        {:status 500 :body {:error (.getMessage e)}}))))

;; ---------------------------------------------------------------------------
;; Channel handlers
;; ---------------------------------------------------------------------------

(defn list-channels-handler
  "List all channels in the catalog."
  [{:keys [catalog]}]
  (fn [_]
    (try
      {:status 200 :body {:channels (vec (catalog/get-channels catalog))}}
      (catch Exception e
        (log/error e "Error listing channels")
        {:status 500 :body {:error (.getMessage e)}}))))

(defn get-media-by-channel-handler
  "List all media items assigned to a given channel."
  [{:keys [catalog]}]
  (fn [req]
    (try
      (let [channel (get-in req [:parameters :path :channel-name])
            media   (catalog/get-media-by-channel catalog channel)]
        {:status 200 :body {:media (mapv serialize-time-fields media)}})
      (catch Exception e
        (log/error e "Error fetching media by channel")
        {:status 500 :body {:error (.getMessage e)}}))))

;; ---------------------------------------------------------------------------
;; Genre handlers
;; ---------------------------------------------------------------------------

(defn list-genres-handler
  "List all genres in the catalog."
  [{:keys [catalog]}]
  (fn [_]
    (try
      (let [genres (catalog/get-genres catalog)]
        {:status 200 :body {:genres (mapv name genres)}})
      (catch Exception e
        (log/error e "Error listing genres")
        {:status 500 :body {:error (.getMessage e)}}))))

(defn get-media-by-genre-handler
  "List all media items with a given genre."
  [{:keys [catalog]}]
  (fn [req]
    (try
      (let [genre (get-in req [:parameters :path :genre])
            media (catalog/get-media-by-genre catalog genre)]
        {:status 200 :body {:media (mapv serialize-time-fields media)}})
      (catch Exception e
        (log/error e "Error fetching media by genre")
        {:status 500 :body {:error (.getMessage e)}}))))
