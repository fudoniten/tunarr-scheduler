(ns tunarr.scheduler.media.catalog
  "Media catalog integration with Jellyfin or Tunarr."
  (:require [tunarr.scheduler.media :as media]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :refer [instrument]]))

(defprotocol Catalog
  (add-media [catalog media])
  (get-media [catalog])
  (get-media-by-id [catalog media-id])
  (get-media-by-library-id [catalog library-id])
  (add-media-tags [catalog media-id tags])
  (update-channels [catalog channels])
  (update-libraries [catalog libraries])
  (add-media-channels [catalog media-id channels])
  (add-media-genres [catalog media-id channels])
  (get-media-by-channel [catalog channel])
  (get-media-by-tag [catalog tag])
  (get-media-by-genre [catalog genre])
  (close-catalog! [catalog]))

(defmulti initialize-catalog! :type)

(def catalog? (partial satisfies? Catalog))

(s/def ::catalog catalog?)

(s/fdef add-media
  :args (s/cat :catalog ::catalog
               :media   ::media/metadata))

(s/fdef get-media
  :args (s/cat :catalog ::catalog)
  :ret  (s/coll-of ::media/metadata))

(s/fdef get-media-by-id
  :args (s/cat :catalog ::catalog
               :id      ::media/id)
  :ret  ::media/metadata)

(s/fdef get-media-by-library-id
  :args (s/cat :catalog    ::catalog
               :library-id ::media/library-id)
  :ret  (s/coll-of ::media/metadata))

(s/fdef add-media-tags
  :args (s/cat :catalog  ::catalog
               :media-id ::media/id
               :tags     (s/coll-of ::media/tags)))

(s/fdef update-channels
  :args (s/cat :catalog  ::catalog
               :channels ::media/channel-descriptions))

(s/fdef update-libraries
  :args (s/cat :catalog   ::catalog
               :libraries (s/map-of ::media/library-name
                                    ::media/library-id)))

(s/fdef add-media-channels
  :args (s/cat :catalog  ::catalog
               :media-id ::media/id
               :channels ::media/channel-descriptions))

(s/fdef add-media-genres
  :args (s/cat :catalog  ::catalog
               :media-id ::media/id
               :genres   (s/coll-of ::media/genre)))

(s/fdef get-media-by-channel
  :args (s/cat :catalog ::catalog
               :channel ::media/channel-name)
  :ret  (s/coll-of ::media/metadata))

(s/fdef get-media-by-tag
  :args (s/cat :catalog ::catalog
               :tag     ::media/tag)
  :ret  (s/coll-of ::media/metadata))

(s/fdef get-media-by-genre
  :args (s/cat :catalog ::catalog
               :tag     ::media/genre)
  :ret  (s/coll-of ::media/metadata))

(s/fdef close-catalog!
  :args (s/cat :catalog ::catalog))

(instrument 'add-media)
(instrument 'get-media)
(instrument 'get-media-by-id)
(instrument 'get-media-by-library-id)
(instrument 'add-media-tags)
(instrument 'update-channels)
(instrument 'update-libraries)
(instrument 'add-media-channels)
(instrument 'add-media-genres)
(instrument 'get-media-by-channel)
(instrument 'get-media-by-tag)
(instrument 'get-media-by-genre)
(instrument 'close-catalog!)
