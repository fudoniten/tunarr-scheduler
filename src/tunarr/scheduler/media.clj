(ns tunarr.scheduler.media
  (:require [clojure.spec.alpha :as s])
  (:import java.time.LocalDate))

(s/def ::tag keyword?)
(s/def ::tags (s/coll-of ::tag))

;; DEPRECATED: Hardcoded channel concepts. Channels are a dimension now.
;; Use ::category-name with value "channel" instead.
(s/def ::channel-name keyword?)
(s/def ::channel-fullname string?)
(s/def ::channel-description string?)
(s/def ::channel-id string?)
;; The canonical channel UUID (from the SQL `channel.id` column) is the
;; stable internal key for the layered-grid storage layer. The config
;; carries `::channel-id` (the *PV* UUID used for the daily-slots push) —
;; the TS `channel.id` is a *separate* UUID and is what storage should
;; key on, since it never changes when a channel is renamed. Look it up
;; from the `channel` table by matching `::channel-fullname` (or, as a
;; fallback, by the config key's slug against `channel.name`).
(s/def ::channel-uuid string?)
(s/def ::channel-descriptions
  (s/map-of ::channel-name
            (s/keys :req [::channel-fullname
                          ::channel-id
                          ::channel-uuid
                          ::channel-description])))
(s/def ::channel-names (s/coll-of ::channel-name))

;; DEPRECATED: Hardcoded boolean. Use a dimension like "age-suitability" instead.
(s/def ::kid-friendly? boolean?)

;; DEPRECATED: Hardcoded genre concept. Genres are a dimension now.
;; Use ::category-name with value "genre" instead.
(s/def ::genre keyword)

;; Dimension model (current)
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
(s/def ::media-type #{:movie :series :episode})
(s/def ::item-kind #{:episode :series :movie :filler})
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
  (s/coll-of (s/keys :req [::process-name ::last-run])))
(s/def ::parent-id (s/nilable string?))
(s/def ::season-number (s/nilable pos-int?))
(s/def ::episode-number (s/nilable pos-int?))

;; DEPRECATED: Hardcoded classification bundle. Use dimensions in
;; media_categorization instead of ::channel-names and ::kid-friendly?.
(s/def ::classification
  (s/keys :req [::tags
                ::channel-names
                ::kid-friendly?]))

;; DEPRECATED: Uses hardcoded ::genres, ::channel-names, ::kid-friendly?.
;; These should be replaced by dimensions in ::category-name / ::category-value.
;; See DIMENSION_CLEANUP.md Phase 3.
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
                ::item-kind
                ::subtitles
                ::production-year
                ::subtitles?
                ::premiere
                ::taglines
                ::tags
                ::library-id
                ::kid-friendly?]
          :opt [::process-timestamps
                ::classification
                ::parent-id
                ::season-number
                ::episode-number]))

