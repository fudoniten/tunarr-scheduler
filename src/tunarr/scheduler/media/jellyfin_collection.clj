(ns tunarr.scheduler.media.jellyfin-collection
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.instant :refer [read-instant-date]]
            [cemerick.url :as url]
            [taoensso.timbre :as log]
            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.media.collection :as collection]
            [camel-snake-kebab.core :refer [->kebab-case-keyword]])
  (:import [java.time Instant]))

(defn jellyfin-request [{api-key :api-key} url]
  (let [opts {:headers {"X-Emby-Token" api-key}}]
    (log/info "Fetching Jellyfin resources" {:url url})
    (-> (http/get url opts)
        :body
        (json/parse-string true))))

(defn build-url
  [base-url & {:keys [params path]}]
  (-> (url/url base-url path)
      (assoc :query params)
      str))

(def JELLYFIN_DEFAULT_FIELDS
  ["AirTime"
   "Id"
   "ProductionYear"
   "HasSubtitles"
   "PremiereDate"
   "Name"
   "Type"
   "OfficialRating"
   "CriticRating"
   "CommunityRating"
   "Tags"
   "Overview"
   "Genres"
   "Taglines"])

(defn transform-field
  [i in out f]
  (assoc i out (f (get i in))))

#_(defn iso->pretty
  [d]
  (if-not d
    nil
    (let [fmt (DateTimeFormatter/ofPattern "MMM d, uuuu" Locale/US)]
      (.format fmt
               (-> (Instant/parse d)
                   (ZonedDateTime/ofInstant (ZoneId/of "America/Los_Angeles")))))))

(defn parse-jellyfin-item [item]
  (letfn [(default [d] (fn [o] (or o d)))]
    (-> item
        (transform-field :Name ::media/name
                         identity)
        (transform-field :Overview ::media/overview
                         identity)
        (transform-field :Genres ::media/genres
                         (fn [genres] (map ->kebab-case-keyword genres)))
        (transform-field :CommunityRating ::media/community-rating
                         identity)
        (transform-field :CriticRating ::media/critic-rating
                         identity)
        (transform-field :OfficialRating ::media/rating
                         identity)
        (transform-field :Id ::media/id
                         identity)
        (transform-field :Type ::media/type
                         ->kebab-case-keyword)
        (transform-field :ProductionYear ::media/production-year
                         identity)
        (transform-field :Subtitles ::media/subtitles?
                         (fn [s] (or s false)))
        (transform-field :PremiereDate ::media/premiere
                         (fn [d] (or (some-> d read-instant-date)
                                    (Instant/now))))
        (transform-field :Tags ::media/tags
                         (default []))
        (transform-field :Taglines ::media/taglines
                         (fn [tags] (some->> tags
                                            (or [])
                                            (map ->kebab-case-keyword)))))))

(defn jellyfin:fetch-library-items
  [{:keys [base-url libraries] :as config} library]
  (if-let [library-id (get libraries library)]
    (let [url (build-url base-url
                         :path   "/Items"
                         :params {:Recursive        true
                                  :SortBy           "SortName"
                                  :ParentId         library-id
                                  :IncludeItemTypes "Movie,Series"
                                  :Fields           (str/join "," JELLYFIN_DEFAULT_FIELDS)})]
      (->> (jellyfin-request config url)
           :Items
           (map parse-jellyfin-item)
           (map (fn [m] (assoc m ::media/library-id library-id)))))
    (throw (ex-info (format "media library not found: %s" library)
                    {:library library}))))

(defrecord JellyfinMediaCollection [config]
  collection/MediaCollection
  (get-library-items [_ library]
    (for [md (jellyfin:fetch-library-items config library)]
      (if (s/invalid? (s/conform ::media/metadata md))
        (do (log/error (s/explain ::media/metadata md))
            (throw (ex-info "invalid metadata" {:metadata md :error (s/explain-data ::media/metadata md)})))
        md)))
  (close! [_] (log/info "closed jellyfin media collection")))

(s/def ::library-name keyword?)
(s/def ::library-id string?)
(s/def ::libraries (s/map-of ::library-name ::library-id))

(s/def ::collection-config
  (s/keys :req-un [::api-key ::base-url ::verbose ::libraries]))

(defmethod collection/initialize-collection! :jellyfin
  [config]
  (let [checked-config (s/conform ::collection-config config)]
    (if (s/invalid? checked-config)
      (throw (ex-info "invalid collection spec"
                      {:error (s/explain-data ::collection-config config)}))
      (->JellyfinMediaCollection config))))
