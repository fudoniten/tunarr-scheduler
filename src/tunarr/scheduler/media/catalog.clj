(ns tunarr.scheduler.media.catalog
  "Media catalog integration with Jellyfin or Tunarr."
  (:require [tunarr.scheduler.media :as media]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :refer [instrument]]))

(defprotocol Catalog
  (add-media! [catalog media])
  (add-media-batch! [catalog media-items])
  (get-media [catalog])
  (get-media-by-id [catalog media-id])
  (get-media-by-library-id [catalog library-id])
  (get-media-by-library [catalog library])
  (get-media-by-kind [catalog library-name kind])  ; NEW: Filter by item_kind
  (get-filler-items [catalog library-name])        ; NEW: Convenience for filler
  (count-media-by-kind [catalog library-name])     ; NEW: Count by kind

  ;; Filter top-level media (movies/series) in a library by an optional
  ;; item_kind and/or a case-insensitive substring match against name/overview.
  ;; opts is a map with optional :kind and :q keys.
  (search-media-by-library-id [catalog library-id opts])
  (get-tags [catalog])

  ;; DEPRECATED: Hardcoded channel concept. Channels are a dimension now.
  ;; Use get-media-categories with "channel" dimension instead.
  ;; See DIMENSION_CLEANUP.md Phase 3.
  (get-channels [catalog])

  ;; DEPRECATED: Hardcoded genre concept. Genres are a dimension now.
  ;; Use get-media-categories with "genre" dimension instead.
  ;; See DIMENSION_CLEANUP.md Phase 3.
  (get-genres [catalog])

  (get-media-tags [catalog media-id])
  (add-media-tags! [catalog media-id tags])
  (set-media-tags! [catalog media-id tags])

  ;; DEPRECATED: Hardcoded channel update. Channels are a dimension now.
  ;; Use set-media-category-values! with "channel" dimension instead.
  ;; See DIMENSION_CLEANUP.md Phase 3.
  (update-channels! [catalog channels])

  (update-libraries! [catalog libraries])

  ;; DEPRECATED: Hardcoded channel assignment. Channels are a dimension now.
  ;; Use add-media-category-values! with "channel" dimension instead.
  ;; See DIMENSION_CLEANUP.md Phase 3.
  (add-media-channels! [catalog media-id channels])

  ;; DEPRECATED: Hardcoded genre assignment. Genres are a dimension now.
  ;; Use add-media-category-values! with "genre" dimension instead.
  ;; See DIMENSION_CLEANUP.md Phase 3.
  (add-media-genres! [catalog media-id channels])

  (add-media-taglines! [catalog media-id taglines])

  ;; DEPRECATED: Hardcoded channel filter. Channels are a dimension now.
  ;; Use get-media-by-tag with "channel:NAME" tag instead.
  ;; See DIMENSION_CLEANUP.md Phase 3.
  (get-media-by-channel [catalog channel])

  (get-media-by-tag [catalog tag])

  ;; DEPRECATED: Hardcoded genre filter. Genres are a dimension now.
  ;; Use get-media-by-tag with "genre:NAME" tag instead.
  ;; See DIMENSION_CLEANUP.md Phase 3.
  (get-media-by-genre [catalog genre])

  (get-media-process-timestamps [catalog media-id])
  (get-tag-samples [catalog])
  (delete-tag! [catalog tag])
  (rename-tag! [catalog tag new-tag])
  (batch-rename-tags! [catalog tag-pairs])
  (update-process-timestamp! [catalog media-id process])
  (delete-process-timestamp! [catalog media-id process])
  (delete-library-process-timestamps! [catalog library process])
  (close-catalog! [catalog])
  (get-media-category-values [catalog media-id category])
  (add-media-category-value! [catalog media-id category value rationale])
  (add-media-category-values! [catalog media-id category values])
  (set-media-category-values! [catalog media-id category values])
  (get-media-categories [catalog media-id])
  (delete-media-category-value! [catalog media-id category value])
  (delete-media-category-values! [catalog media-id category])

  ;; Remove a dimension value across ALL media. Used by vocabulary cleanup to
  ;; purge invalid/hallucinated dimension values catalog-wide (mirrors the
  ;; global delete-tag!). See tunarr.scheduler.curation.dimensions.
  (purge-category-value! [catalog category value])
  (get-episodes-by-series [catalog series-id])
  (get-episode [catalog series-id season-number episode-number])
  (get-effective-tags [catalog media-id])
  (get-effective-categories [catalog media-id])
  (get-library-id [catalog library])
  (enrich-media-with-timestamps [catalog media])

  ;; NEW: Browse dimensions across the catalog
  (get-all-dimensions [catalog])
  (get-dimension-values [catalog dimension])
  (get-media-by-category-value [catalog category value]))

(defmulti initialize-catalog! :type)

(def catalog? (partial satisfies? Catalog))

(s/def ::catalog catalog?)

(s/fdef add-media
  :args (s/cat :catalog ::catalog
               :media   ::media/metadata))

(s/fdef add-media-batch
  :args (s/cat :catalog     ::catalog
               :media-items (s/coll-of ::media/metadata)))

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

(s/fdef get-media-by-library
  :args (s/cat :catalog ::catalog
               :library ::media/library-name)
  :ret  (s/coll-of ::media/metadata))

(s/fdef search-media-by-library-id
  :args (s/cat :catalog    ::catalog
               :library-id ::media/library-id
               :opts       map?)
  :ret  (s/coll-of ::media/metadata))

(s/fdef get-tags
  :args (s/cat :catalog ::catalog)
  :ret  ::media/tags)

(s/fdef get-media-tags
  :args (s/cat :catalog ::catalog
               :id      ::media/id)
  :ret  ::media/tags)

(s/fdef add-media-tags
  :args (s/cat :catalog  ::catalog
               :media-id ::media/id
               :tags     (s/coll-of ::media/tags)))

(s/fdef set-media-tags
  :args (s/cat :catalog  ::catalog
               :media-id ::media/id
               :tags     (s/coll-of ::media/tags)))

;; DEPRECATED: Hardcoded channel update. See protocol note above.
(s/fdef update-channels
  :args (s/cat :catalog  ::catalog
               :channels ::media/channel-descriptions))

(s/fdef update-libraries
  :args (s/cat :catalog   ::catalog
               :libraries (s/map-of ::media/library-name
                                    ::media/library-id)))

;; DEPRECATED: Hardcoded channel assignment. See protocol note above.
(s/fdef add-media-channels
  :args (s/cat :catalog  ::catalog
               :media-id ::media/id
               :channels ::media/channel-descriptions))

;; DEPRECATED: Hardcoded genre assignment. See protocol note above.
(s/fdef add-media-genres
  :args (s/cat :catalog  ::catalog
               :media-id ::media/id
               :genres   (s/coll-of ::media/genre)))

(s/fdef add-media-taglines
  :args (s/cat :catalog  ::catalog
               :media-id ::media/id
               :taglines (s/coll-of string?)))

;; DEPRECATED: Hardcoded channel filter. See protocol note above.
(s/fdef get-media-by-channel
  :args (s/cat :catalog ::catalog
               :channel ::media/channel-name)
  :ret  (s/coll-of ::media/metadata))

(s/fdef get-media-by-tag
  :args (s/cat :catalog ::catalog
               :tag     ::media/tag)
  :ret  (s/coll-of ::media/metadata))

;; DEPRECATED: Hardcoded genre filter. See protocol note above.
(s/fdef get-media-by-genre
  :args (s/cat :catalog ::catalog
               :tag     ::media/genre)
  :ret  (s/coll-of ::media/metadata))

(s/fdef get-media-process-timestamps
  :args (s/cat :catalog ::catalog
               :tag     ::media/metadata)
  :ret  (s/coll-of ::media/metadata))

(s/def ::tag string?)
(s/def ::usage-count int?)
(s/def ::example-titles (s/coll-of string?))
(s/def ::tag-sample (s/keys :req-un [::tag ::usage-count ::example-titles]))

(s/fdef get-tag-samples
  :args (s/cat :catalog ::catalog)
  :ret  (s/coll-of ::tag-sample))

(s/fdef close-catalog!
  :args (s/cat :catalog ::catalog))

(s/fdef get-media-category-values
  :args (s/cat :catalog  ::catalog
               :media-id ::media/id
               :category ::media/category-name)
  :ret  (s/coll-of ::media/category-value))

(s/fdef add-media-category-value!
  :args (s/cat :catalog  ::catalog
               :media-id ::media/id
               :category ::media/category-name
               :value ::media/category-value))

(s/fdef add-media-category-values!
  :args (s/cat :catalog  ::catalog
               :media-id ::media/id
               :category ::media/category-name
               :values (s/coll-of ::media/category-value)))

(s/fdef set-media-category-values!
  :args (s/cat :catalog  ::catalog
               :media-id ::media/id
               :category ::media/category-name
               :values (s/coll-of ::media/category-value)))

(s/fdef get-media-categories
  :args (s/cat :catalog  ::catalog
               :media-id ::media/id)
  :ret  (s/map-of ::media/category-name
                  (s/coll-of ::media/category-value)))

(s/fdef delete-media-category-value!
  :args (s/cat :catalog  ::catalog
               :media-id ::media/id
               :category ::media/category-name
               :value    ::media/category-value))

(s/fdef delete-media-category-values!
  :args (s/cat :catalog  ::catalog
               :media-id ::media/id
               :category ::media/category-name))

(s/fdef get-episodes-by-series
  :args (s/cat :catalog   ::catalog
               :series-id ::media/id)
  :ret  (s/coll-of ::media/metadata))

(s/fdef get-episode
  :args (s/cat :catalog        ::catalog
               :series-id      ::media/id
               :season-number  pos-int?
               :episode-number pos-int?)
  :ret  (s/nilable ::media/metadata))

(s/fdef get-effective-tags
  :args (s/cat :catalog  ::catalog
               :media-id ::media/id)
  :ret  ::media/tags)

(s/fdef get-effective-categories
  :args (s/cat :catalog  ::catalog
               :media-id ::media/id)
  :ret  (s/map-of ::media/category-name
                  (s/coll-of ::media/category-value)))

(s/fdef get-all-dimensions
  :args (s/cat :catalog ::catalog)
  :ret  (s/coll-of [:map
                    [:name ::media/category-name]
                    [:value-count int?]]))

(s/fdef get-dimension-values
  :args (s/cat :catalog ::catalog
               :dimension ::media/category-name)
  :ret  (s/coll-of [:map
                    [:value ::media/category-value]
                    [:usage-count int?]]))

(instrument 'add-media)
(instrument 'add-media-batch)
(instrument 'get-media)
(instrument 'get-media-by-id)
(instrument 'get-media-by-library-id)
(instrument 'search-media-by-library-id)
(instrument 'get-media-by-library)
(instrument 'add-media-tags)
(instrument 'set-media-tags)
(instrument 'update-channels)
(instrument 'update-libraries)
(instrument 'add-media-channels)
(instrument 'add-media-genres)
(instrument 'add-media-taglines)
(instrument 'get-media-by-channel)
(instrument 'get-media-by-tag)
(instrument 'get-media-by-genre)
(instrument 'get-media-process-timestamps)
(instrument 'get-tag-samples)
(instrument 'close-catalog!)
(instrument 'get-media-category-values)
(instrument 'add-media-category-value!)
(instrument 'add-media-category-values!)
(instrument 'set-media-category-values!)
(instrument 'get-media-categories)
(instrument 'delete-media-category-value!)
(instrument 'delete-media-category-values!)
(instrument 'get-episodes-by-series)
(instrument 'get-episode)
(instrument 'get-effective-tags)
(instrument 'get-effective-categories)
(instrument 'get-all-dimensions)
(instrument 'get-dimension-values)
