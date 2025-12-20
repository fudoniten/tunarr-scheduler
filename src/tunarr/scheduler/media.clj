(ns tunarr.scheduler.media
  (:require [clojure.spec.alpha :as s])
  (:import java.time.LocalDate))

(s/def ::tag keyword?)
(s/def ::tags (s/coll-of ::tag))
(s/def ::channel-name keyword?)
(s/def ::channel-fullname string?)
(s/def ::channel-description string?)
(s/def ::channel-id string?)
(s/def ::channel-descriptions
  (s/map-of ::channel-name
            (s/keys :req [::channel-fullname
                          ::channel-id
                          ::channel-description])))
(s/def ::channel-names (s/coll-of ::channel-name))
(s/def ::kid-friendly? boolean?)
(s/def ::genre keyword)

(s/def ::category-name keyword?)
(s/def ::category-value keyword?)

(defn rating? [n] (and (number? n) (<= 0 n 100)))

(defn year? [n] (and (number? n) (<= 0 n 2100)))

(defn date? [o] (instance? LocalDate o))

(s/def ::name string?)
(s/def ::overview (s/nilable string?))
(s/def ::genres (s/coll-of ::genre))
(s/def ::community-rating (s/nilable rating?))
(s/def ::critic-rating (s/nilable rating?))
(s/def ::rating (s/nilable string?))
(s/def ::id string?)
(s/def ::media-type #{:movie :series})
(s/def ::production-year year?)
(s/def ::subtitles boolean?)
(s/def ::premiere date?)
(s/def ::taglines (s/coll-of string?))
(s/def ::library-id string?)
(s/def ::library-name keyword?)
(s/def ::rationale string?)
(s/def ::timestamp inst?)
(s/def ::process-name keyword?)
(s/def ::last-run ::timestamp)
(s/def ::process-timestamps
  (s/map-of ::process-name ::last-run))
(s/def ::classification
  (s/keys :req [::tags
                ::channel-names
                ::kid-friendly?]))

(s/def ::metadata
  (s/keys :req [::name
                ::overview
                ::genres
                ::community-rating
                ::critic-rating
                ::rating
                ::id
                ::type
                ::media-type
                ::subtitles
                ::production-year
                ::subtitles?
                ::premiere
                ::taglines
                ::tags
                ::library-id
                ::kid-friendly?]
          :opt [::process-timestamps
                ::classification]))

