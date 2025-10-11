(ns tunarr.scheduler.media.collection
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [cemerick.url :as url]
            [taoensso.timbre :as log]
            [tunarr.scheduler.media :as media]
            [camel-snake-kebab.core :refer [->kebab-case-keyword]])
  (:import [java.time Instant ZoneId ZonedDateTime]
           [java.time.format DateTimeFormatter]
           [java.util Locale]))

(def ::library-id string?)

(defprotocol MediaCollection
  (fetch-library-items [self library-id]))

(def media-collection? (partial satisfies? MediaCollection))

(s/fdef fetch-library-items
  :args (s/cat :self media-collection? :library-id ::library-id)
  :ret  (s/coll-of ::media/metadata))

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
   "Overview"
   "Genres"
   "Taglines"])

(defn transform-field
  [i in out f]
  (assoc i out (f (get i in))))

(defn iso->pretty
  [d]
  (if-not d
    nil
    (let [fmt (DateTimeFormatter/ofPattern "MMM d, uuuu" Locale/US)]
      (.format fmt
               (-> (Instant/parse d)
                   (ZonedDateTime/ofInstant (ZoneId/of "America/Los_Angeles")))))))

(defn parse-jellyfin-item [item]
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
                       iso->pretty)
      (transform-field :Taglines ::media/taglines
                       identity)))

(defn jellyfin:fetch-library-items
  [{:keys [base-url] :as config} library-id]
  (let [url (build-url base-url
                       :path   "/Items"
                       :params {:Recursive        true
                                :SortBy           "SortName"
                                :ParentId         library-id
                                :IncludeItemTypes "Movie,Series"
                                :Fields           JELLYFIN_DEFAULT_FIELDS})]
    (->> (jellyfin-request config url)
         :Items
         (map parse-jellyfin-item)
         (map (fn [m] (assoc m ::media/library-id library-id)))
         (map (partial s/conform ::media/metadata)))))

(defrecord JellyfinMediaCollection [config]
  MediaCollection
  (fetch-library-items [_ library-id]
    (jellyfin:fetch-library-items config library-id)))
