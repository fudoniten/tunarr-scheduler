(ns tunarr.scheduler.media
  (:require [clojure.spec.alpha :as s])
  (:import java.time.Instant))

(s/def ::tag keyword?)
(s/def ::tags (s/coll-of ::tag))
(s/def ::channel-name keyword?)
(s/def ::channel-descriptions
  (s/map-of ::channel-name string?))
(s/def ::channel-names (s/coll-of ::channel-name))
(s/def ::kid-friendly? boolean?)
(s/def ::genre keyword)

(defn rating? [n] (and (number? n) (<= 0 n 100)))

(defn year? [n] (and (number? n) (<= 0 n 2100)))

(defn date? [o] (instance? Instant o))

(s/def ::name string?)
(s/def ::overview string?)
(s/def ::genres (s/coll-of ::genre))
(s/def ::community-rating rating?)
(s/def ::critic-rating (s/nilable rating?))
(s/def ::rating (s/nilable string?))
(s/def ::id string?)
(s/def ::type #{:movie :series})
(s/def ::production-year year?)
(s/def ::subtitles? boolean?)
(s/def ::premiere date?)
(s/def ::taglines (s/coll-of string?))
(s/def ::library-id string?)

(def media-fields
  [::name
   ::overview
   ::genres
   ::community-rating
   ::critic-rating
   ::rating
   ::id
   ::type
   ::production-year
   ::subtitles?
   ::premiere
   ::taglines
   ::library-id])

(s/def ::metadata
  (s/keys :req [media-fields]))

(s/def ::classification
  (s/keys :req [::tags
                ::channel-names
                ::kid-friendly?]))

