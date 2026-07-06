(ns tunarr.scheduler.media.sql-catalog
  (:require [tunarr.scheduler.media :as media]
            [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.sql.executor :as executor]

            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :refer [instrument]]

            [honey.sql.helpers :refer [select from where insert-into values on-conflict do-nothing join left-join group-by columns do-update-set delete-from order-by] :as sql]
            [cheshire.core :as json]
            [next.jdbc :as jdbc]
            [taoensso.timbre :as log])
  (:import java.sql.Array
           java.time.LocalDate
           java.util.Date
           java.time.ZoneId))

(def field-map
  {::media/name             :media/name
   ::media/overview         :media/overview
   ::media/community-rating :media/community_rating
   ::media/critic-rating    :media/critic_rating
   ::media/rating           :media/rating
   ::media/id               :media/id
   ::media/type             :media/media_type
   ::media/item-kind        :media/item_kind          ; NEW: item_kind field
   ::media/production-year  :media/production_year
   ::media/subtitles?       :media/subtitles
   ::media/premiere         :media/premiere
   ::media/library-id       :media/library_id
   ::media/kid-friendly?    :media/kid_friendly
   ::media/parent-id        :media/parent_id
   ::media/season-number    :media/season_number
   ::media/episode-number   :media/episode_number
   ::media/tags             :tags
   ::media/channel-names    :channels
   ::media/genres           :genres
   ::media/taglines         :taglines})

(defn pgarray->vec [o]
  (if (instance? Array o)
    (let [arr (.getArray ^Array o)]
      (vec (seq arr)))
    o))

(defn ->local-date [x]
  (cond
    (nil? x) nil
    (instance? LocalDate x) x
    (string? x) (LocalDate/parse x) ; expects yyyy-MM-dd
    (instance? java.sql.Date x) (.toLocalDate ^java.sql.Date x)
    (instance? Date x) (-> ^Date x
                           .toInstant
                           (.atZone (ZoneId/systemDefault))
                           .toLocalDate)
    :else (throw (ex-info "Cannot convert to LocalDate"
                          {:value x :type (type x)}))))

(defn map-over [f] (fn [o] (map f o)))

(def field-transforms
  {:media/name              identity
   :media/overview          identity
   :media/community_rating  identity
   :media/critic_rating     identity
   :media/rating            identity
   :media/id                identity
   :media/media_type        keyword
   :media/item_kind         keyword  ; NEW: Convert string back to keyword
   :media/production_year   identity
   :media/subtitles         identity
   :media/premiere          ->local-date
   :media/library_id        identity
   :media/kid_friendly      identity
   :media/parent_id         identity
   :media/season_number     identity
   :media/episode_number    identity
   :tags                    (comp (partial mapv keyword) (partial remove nil?) pgarray->vec)
   :channels                (comp (partial mapv keyword) (partial remove nil?) pgarray->vec)
   :genres                  (comp (partial mapv keyword) (partial remove nil?) pgarray->vec)
   :taglines                (comp vec (partial remove nil?) pgarray->vec)})

(defn media->row
  "Rename the media map keys to match the SQL schema."
  [media]
  (reduce (fn [acc [media-key column]]
            (if (contains? media media-key)
              (assoc acc (keyword (name column)) (get media media-key))
              acc))
          {}
          field-map))

(defn row->media
  [row]
  (reduce (fn [acc [media-key column]]
            (if (contains? row column)
              (let [xf (get field-transforms column identity)]
                (assoc acc media-key (xf (get row column))))
              acc))
          {}
          field-map))

(defn unwrap-result
  [op result]
  (let [[status payload] result]
    (case status
      :ok  payload
      :err (do
             (log/error payload (format "SQL %s failed" op))
             (throw payload))
      (do
        (log/errorf "SQL %s returned unexpected status: %s" op status)
        (throw (ex-info "unexpected SQL executor status"
                        {:op     op
                         :status status
                         :result result}))))))

(defn sql:exec-with-tx!
  [executor queries]
  (unwrap-result "exec-with-tx!" (deref (executor/exec-with-tx! executor queries))))

(defn sql:exec!
  [executor query]
  (unwrap-result "exec!" (deref (executor/exec! executor query))))

(defn sql:fetch!
  [executor query]
  (unwrap-result "fetch!" (deref (executor/fetch! executor query))))

(defn sql:insert-tags
  [tags]
  (-> (insert-into :tag)
      (columns :name)
      (values (map (comp vector name) tags))
      (on-conflict :name) (do-nothing)))

(defn sql:delete-media-tags!
  [media-id tags]
  (-> (delete-from :media_tags)
      (where [:= :media_id media-id]
             [:in :tag (map name tags)])))

(defn sql:get-tags
  []
  (-> (select :name) (from :tag)))

(defn sql:get-tag-samples
  []
  (-> (select [:media_tags.tag :tag]
              [[:count [:distinct :media_tags.media_id]] :usage_count]
              [[:array_agg [:distinct :media.name]] :example_titles])
      (from :media_tags)
      (left-join :media [:= :media_tags.media_id :media.id])
      (group-by :media_tags.tag)
      (order-by [:usage_count :desc])))

(defn sql:get-library-id
  [library]
  (-> (select :id)
      (from :library)
      (where [:= [:lower :name] [:lower (name library)]])))

(defn sql:insert-media-tags
  [media-id tags]
  (-> (insert-into :media_tags)
      (columns :media_id :tag)
      (values (map (fn [tag] [media-id (name tag)]) tags))
      (on-conflict :tag :media_id) (do-nothing)))

;; DEPRECATED: Hardcoded genre table. Genres are dimensions in media_categorization now.
;; Use sql:add-media-category-values! with "genre" category instead.
;; See DIMENSION_CLEANUP.md Phase 3.
(defn sql:insert-genres
  [genres]
  (-> (insert-into :genre)
      (columns :name)
      (values (map (comp vector name) genres))
      (on-conflict :name)
      (do-nothing)))

;; DEPRECATED: Hardcoded media_genres table. Genres are dimensions now.
;; Use sql:add-media-category-values! with "genre" category instead.
;; See DIMENSION_CLEANUP.md Phase 3.
(defn sql:insert-media-genres
  [media-id genres]
  (-> (insert-into :media_genres)
      (columns :media_id :genre)
      (values (map (fn [genre] [media-id (name genre)]) genres))
      (on-conflict :media_id :genre) (do-nothing)))

;; DEPRECATED: Hardcoded channel table. Channels are dimensions in media_categorization now.
;; Use sql:add-media-category-values! with "channel" category instead.
;; See DIMENSION_CLEANUP.md Phase 3.
(defn sql:insert-channels
  [channels]
  (-> (insert-into :channel)
      (columns :name :full_name :id :description)
      (values (map (fn [[channel {:keys [::media/channel-id
                                          ::media/channel-fullname
                                          ::media/channel-description]}]]
                     [(name channel) channel-fullname channel-id channel-description])
                    channels))
      (on-conflict :name) (do-update-set :name :full_name :id :description)))

(defn sql:insert-libraries
  [libraries]
  (-> (insert-into :library)
      (columns :id :name)
      (values (into [] (map (fn [[k v]] [v (name k)])) libraries))
      (on-conflict :name) (do-update-set :id)))

(s/fdef sql:insert-channels
  :args (s/cat :channels ::media/channel-descriptions))

(instrument 'sql:insert-channels)

;; DEPRECATED: Hardcoded media_channels table. Channels are dimensions now.
;; Use sql:add-media-category-values! with "channel" category instead.
;; See DIMENSION_CLEANUP.md Phase 3.
(defn sql:insert-media-channels
  [media-id channels]
  (-> (insert-into :media_channels)
      (columns :media_id :channel)
      (values (map (fn [channel] [media-id (name channel)]) channels))
      (on-conflict :media_id :channel) (do-nothing)))

(defn sql:insert-media-taglines
  [media-id taglines]
  (-> (insert-into :media_taglines)
      (columns :media_id :tagline)
      (values (map (fn [tagline] [media-id tagline])
                   taglines))
      (on-conflict :media_id :tagline) (do-nothing)))

(defn sql:delete-tag
  [tag]
  (-> (delete-from :tag)
      (where [:= :name (name tag)])))

(defn sql:rename-tag
  [tag new-tag]
  (-> (sql/update :tag)
      (sql/set {:name (name new-tag)})
      (where [:= :name (name tag)])))

(defn sql:get-tag
  [tag]
  (-> (select :name)
      (from :tag)
      (where [:= :name (name tag)])))

;; DEPRECATED: Hardcoded channel table. Channels are dimensions now.
;; Use get-media-categories or tag-based queries instead.
;; See DIMENSION_CLEANUP.md Phase 3.
(defn sql:get-channels
  []
  (-> (select :name :full_name :id :description)
      (from :channel)
      (order-by :name)))

;; DEPRECATED: Hardcoded genre table. Genres are dimensions now.
;; Use get-media-categories or tag-based queries instead.
;; See DIMENSION_CLEANUP.md Phase 3.
(defn sql:get-genres
  []
  (-> (select :name) (from :genre) (order-by :name)))

(defn sql:get-media-tags
  [media-id]
  (-> (select :tag)
      (from :media_tags)
      (where [:= :media_id media-id])))

(defn sql:retag-media
  [tag new-tag]
  (-> (sql/update [:media_tags :mt1])
      (sql/set {:tag (name new-tag)})
      (where [:= :tag (name tag)]
             [:not
              [:exists (-> (select :1)
                           (from [:media_tags :mt2])
                           (where [:and
                                   [:= :mt2.tag (name new-tag)]
                                   [:= :mt2.media_id :mt1.media_id]]))]])))

(defn sql:get-media-category-values
  [media-id category]
  (-> (select :category_value)
      (from :media_categorization)
      (where [:= :media_id media-id]
             [:= :category (name category)])))

(defn sql:add-media-category-values!
  [media-id category category-values]
  (-> (insert-into :media_categorization)
      (columns :media_id :category :category_value :rationale)
      (values (map (fn [{:keys [::media/category-value
                                ::media/rationale]}]
                     [media-id
                      (name category)
                      (name category-value)
                      rationale])
                   category-values))
      (on-conflict :media_id :category :category_value) (do-nothing)))

(defn sql:get-media-categories [media-id]
  (-> (select :category
              [[:array_agg [:distinct :media_categorization.category_value]] :values])
      (from :media_categorization)
      (where [:= :media_id media-id])
      (group-by :category)))

(defn sql:delete-media-category-value! [media-id category value]
  (-> (delete-from :media_categorization)
      (where [:= :media_id media-id]
             [:= :category (name category)]
             [:= :category_value (name value)])))

(defn sql:delete-media-category-values! [media-id category]
  (-> (delete-from :media_categorization)
      (where [:= :media_id media-id]
             [:= :category (name category)])))

(defn sql:purge-category-value!
  "Delete a (category, value) pair across all media."
  [category value]
  (-> (delete-from :media_categorization)
      (where [:= :category (name category)]
             [:= :category_value (name value)])))

(defn sql:get-all-dimensions
  "List all unique dimension (category) names with their value counts."
  []
  (-> (select :category
              [[:count [:distinct :category_value]] :value-count])
      (from :media_categorization)
      (group-by :category)))

(defn sql:get-dimension-values
  "List all unique values for a given dimension, with usage counts."
  [dimension]
  (-> (select :category_value
              [[:count :*] :usage-count])
      (from :media_categorization)
      (where [:= :category (name dimension)])
      (group-by :category_value)))

;; ---------------------------------------------------------------------------
;; Per-media grounding context (media_context table)
;;
;; `links` is stored as a JSON-encoded array of URL strings (a plain text
;; column) so the schema stays portable across Postgres and the H2 test DB.
;; ---------------------------------------------------------------------------

(defn- ->iso
  "Coerce a SQL timestamp value to an ISO-8601 string, defensively handling the
   java.sql.Timestamp / java.time types different drivers return."
  [x]
  (cond
    (nil? x)                        nil
    (instance? java.sql.Timestamp x) (str (.toInstant ^java.sql.Timestamp x))
    :else                           (str x)))

(defn context-row->map
  "Convert a media_context row into the internal context map, decoding the
   JSON-encoded `links` column back into a vector of strings."
  [{:keys [media_context/text media_context/links media_context/summary
           media_context/source media_context/operator_edited
           media_context/updated_at]}]
  {:text            text
   :links           (vec (when links (json/parse-string links)))
   :summary         summary
   :source          source
   :operator-edited (boolean operator_edited)
   :updated-at      (->iso updated_at)})

(defn sql:get-media-context
  [media-id]
  (-> (select :media_id :text :links :summary :source :operator_edited :updated_at)
      (from :media_context)
      (where [:= :media_id media-id])))

(defn sql:upsert-media-context
  [media-id {:keys [text links summary source operator-edited]}]
  (let [row {:media_id        media-id
             :text            text
             :links           (json/generate-string (vec (or links [])))
             :summary         summary
             :source          source
             :operator_edited (boolean operator-edited)
             :updated_at      [:now]}]
    (-> (insert-into :media_context)
        (values [row])
        (on-conflict :media_id)
        (do-update-set :text :links :summary :source :operator_edited :updated_at))))

(defn sql:delete-media-context
  [media-id]
  (-> (delete-from :media_context)
      (where [:= :media_id media-id])))

(defn tag-exists?
  [executor tag]
  (some #(= tag (:tag/name %))
        (sql:fetch! executor (sql:get-tag tag))))

(defn sql:update-process-timestamp
  [media-id process]
  (-> (insert-into :media_process_timestamp)
      (columns :media_id :process :last_run_at)
      (values [[media-id (name process) [:now]]])
      (on-conflict :media_id :process)
      (do-update-set {:last_run_at (keyword "excluded" "last_run_at")})))

(defn sql:delete-process-timestamp
  [media-id process]
  (-> (delete-from :media_process_timestamp)
      (where [:= :media_id media-id]
             [:= :process (name process)])))

(defn sql:delete-library-process-timestamps
  [library-id process]
  (-> (delete-from :media_process_timestamp)
      (where [:= :process (name process)]
             [:in :media_id (-> (select :id)
                                (from :media)
                                (where [:= :library_id library-id]))])))

(defn optional [pred lst]
  (if pred lst []))

(def media-upsert-columns
  "Scalar columns on the `media` table that are sourced from upstream
  (Jellyfin / Pseudovision) and are safe to overwrite when a later sync
  delivers fresh data for an existing `media.id`.

  Metadata generated inside the scheduler (tags, channels, genres,
  taglines, categorization) lives in join tables and is preserved
  because the join-table inserts use `on-conflict do-nothing`, which
  merges new associations in without touching existing ones."
  [:name :overview :community_rating :critic_rating :rating :media_type
   :item_kind :production_year :subtitles :premiere :library_id :kid_friendly
   :parent_id :season_number :episode_number])

(def ^:private media-join-columns
  "Keys that media->row emits from the multi-valued field-map entries but which
   are NOT columns on the `media` table — they are association collections
   (tags, channels, genres, taglines) persisted into their own join tables. They
   must be stripped from the media INSERT/upsert row; honey.sql would otherwise
   render a value like [:action :adventure] as the SQL function call
   `action(adventure)`. The join tables are populated by the separate
   sql:insert-media-* statements."
  [:tags :channels :genres :taglines])

(defn sql:add-media
  "Upsert a single media item.

  On `media.id` conflict, scalar columns in [[media-upsert-columns]] are
  refreshed from the incoming row while join-table associations
  (`media_tags`, `media_channels`, `media_genres`, `media_taglines`) are
  merged additively — existing curation metadata is preserved."
  [{:keys [::media/id
           ::media/tags
           ::media/channels
           ::media/genres
           ::media/taglines]
    :as media}]
  (let [row (-> (apply dissoc (media->row media) media-join-columns)
                (update :media_type name)
                (update :item_kind name))  ; NEW: Convert item_kind keyword to string
        update-cols (filterv #(contains? row %) media-upsert-columns)
        base (-> (insert-into :media) (values [row]) (on-conflict :id))]
    (concat (optional (seq tags) [(sql:insert-tags tags)])
            (optional (seq genres) [(sql:insert-genres genres)])
            [(if (seq update-cols)
               (apply do-update-set base update-cols)
               (do-nothing base))]
            (optional (seq tags) [(sql:insert-media-tags id tags)])
            (optional (seq genres) [(sql:insert-media-genres id genres)])
            (optional (seq channels) [(sql:insert-media-channels id channels)])
            (optional (seq taglines) [(sql:insert-media-taglines id taglines)]))))

(defn- batch-associations
  "Collect [media-id value] join-table rows for key `k` across a batch of
  media items, converting each raw value with `value-fn`."
  [media-items k value-fn]
  (mapcat (fn [item]
            (map (fn [v] [(::media/id item) (value-fn v)])
                 (get item k)))
          media-items))

(defn- sql:insert-associations
  "Batch insert join-table rows, merging additively (do-nothing on
  conflict) so existing curation metadata is preserved. Returns [] when
  there are no rows."
  [table value-col rows]
  (optional (seq rows)
            [(-> (insert-into table)
                 (columns :media_id value-col)
                 (values rows)
                 (on-conflict :media_id value-col)
                 (do-nothing))]))

(defn sql:add-media-batch
  "Generate SQL queries to batch upsert multiple media items.

  This is more efficient than calling sql:add-media for each item individually,
  as it groups inserts by type and reduces the number of transactions.
  Items are sorted so that series are inserted before their episodes to
  satisfy the parent_id foreign key constraint.

  Upsert semantics match [[sql:add-media]]: scalar media columns are
  refreshed from the incoming batch, while join-table metadata is merged
  additively so curation data is preserved."
  [media-items]
  (let [;; Sort: non-episodes first (movies, series), then episodes
        ;; This ensures parent records exist before child FK references
        sorted-items (sort-by #(if (= :episode (::media/type %)) 1 0) media-items)
        rows (mapv (fn [media]
                     (-> (apply dissoc (media->row media) media-join-columns)
                         (update :media_type name)
                         (update :item_kind name)))
                   sorted-items)
        all-tags (distinct (mapcat ::media/tags sorted-items))
        all-genres (distinct (mapcat ::media/genres sorted-items))]
    (concat
     ;; Insert all unique tags and genres first
     (optional (seq all-tags) [(sql:insert-tags all-tags)])
     (optional (seq all-genres) [(sql:insert-genres all-genres)])
     ;; Batch upsert all media rows. Only refresh columns that every
     ;; row in the batch provides — honey.sql pads missing keys with
     ;; NULL, and we don't want an upsert to clobber existing fields
     ;; because some sibling item in the batch happened to omit them.
     [(let [update-cols (filterv (fn [col] (every? #(contains? % col) rows))
                                 media-upsert-columns)
            base (-> (insert-into :media) (values rows) (on-conflict :id))]
        (if (seq update-cols)
          (apply do-update-set base update-cols)
          (do-nothing base)))]
     ;; Batch insert join-table associations
     (sql:insert-associations :media_tags :tag
                              (batch-associations sorted-items ::media/tags name))
     (sql:insert-associations :media_genres :genre
                              (batch-associations sorted-items ::media/genres name))
     (sql:insert-associations :media_channels :channel
                              (batch-associations sorted-items ::media/channels identity))
     (sql:insert-associations :media_taglines :tagline
                              (batch-associations sorted-items ::media/taglines identity)))))

(defn sql:get-media-processes-by-id
  [media-id]
  (-> (select :process :last_run_at)
      (from :media_process_timestamp)
      (where [:= :media_id media-id])))

(defn sql:get-media
  []
  (-> (select :media.id
              :media.name
              :media.overview
              :media.community_rating
              :media.critic_rating
              :media.media_type
              :media.production_year
              :media.premiere
              :media.subtitles
              :media.kid_friendly
              :media.library_id
              :media.parent_id
              :media.season_number
              :media.episode_number
              ;; array_agg over a left join with no matching rows yields
              ;; {NULL}; the nils are stripped by field-transforms
              [[:array_agg [:distinct :media_tags.tag]]
               :tags]
              [[:array_agg [:distinct :media_channels.channel]]
               :channels]
              [[:array_agg [:distinct :media_genres.genre]]
               :genres]
              [[:array_agg [:distinct :media_taglines.tagline]]
               :taglines])
      (from :media)
      (left-join :media_tags
                 [:= :media.id :media_tags.media_id])
      (left-join :media_channels
                 [:= :media.id :media_channels.media_id])
      (left-join :media_genres
                 [:= :media.id :media_genres.media_id])
      (left-join :media_taglines
                 [:= :media.id :media_taglines.media_id])
      (group-by :media.id)))

(defn sql:get-top-level-media
  "Get only movies and series (not episodes) - used by library queries
   so the curation loop doesn't iterate over episodes."
  []
  (-> (sql:get-media)
      (where [:in :media.media_type ["movie" "series"]])))

(defn sql:search-top-level-media
  "Top-level media (movies/series) in a library, optionally filtered by
   item_kind and/or a case-insensitive substring match against name/overview."
  [library-id {:keys [kind q]}]
  (cond-> (-> (sql:get-top-level-media)
              (where [:= :media/library_id library-id]))
    kind (where [:= :media/item_kind (name kind)])
    q    (where [:or [:ilike :media/name (str "%" q "%")]
                     [:ilike :media/overview (str "%" q "%")]])))

(defn sql:get-episodes-by-series
  [series-id]
  (-> (sql:get-media)
      (where [:= :media.parent_id series-id])
      (order-by [:media.season_number :asc]
                [:media.episode_number :asc])))

(defn sql:get-episode
  [series-id season-number episode-number]
  (-> (sql:get-media)
      (where [:= :media.parent_id series-id]
             [:= :media.season_number season-number]
             [:= :media.episode_number episode-number])))

(defn sql:get-effective-tags
  "Get the union of an item's own tags and its parent's tags."
  [media-id]
  {:union-all [(-> (select :tag)
                   (from :media_tags)
                   (where [:= :media_id media-id]))
               (-> (select [:mt.tag :tag])
                   (from [:media_tags :mt])
                   (left-join [:media :child] [:= :child.parent_id :mt.media_id])
                   (where [:= :child.id media-id]))]})

(defrecord SqlCatalog [executor]
  catalog/Catalog
  (add-media! [_ media]
    (sql:exec-with-tx! executor (sql:add-media media)))

  (add-media-batch! [_ media-items]
    (sql:exec-with-tx! executor (sql:add-media-batch media-items)))

  (get-media [_]
    ;; Transform rows like every other getter: callers (e.g. the Pseudovision
    ;; full-reconcile sync) read namespaced ::media/* keys and expect the tag/
    ;; genre/channel aggregates decoded from Postgres arrays into keyword vectors.
    (mapv row->media (sql:fetch! executor (sql:get-media))))

  (get-media-by-library-id [this library-id]
    (->> (sql:fetch! executor
                     (-> (sql:get-top-level-media)
                         (where [:= :media/library_id library-id])))
         (map row->media)
         (catalog/enrich-media-with-timestamps this)))

  (get-media-by-library [self library]
    (if-let [library-id (some-> (sql:fetch! executor (sql:get-library-id library))
                                first
                                :library/id)]
      (do (log/info (format "getting media for library id %s" library-id))
          (catalog/get-media-by-library-id self library-id))
      (throw (ex-info (format "library not found: %s" (name library)) {}))))

  ;; NEW: Filter by item_kind
  (get-media-by-kind [this library-name kind]
    (if-let [library-id (some-> (sql:fetch! executor (sql:get-library-id library-name))
                                first
                                :library/id)]
      (do (log/info (format "getting %s items for library %s (id: %s)" (name kind) library-name library-id))
          (->> (sql:fetch! executor
                           (-> (sql:get-top-level-media)
                               (where [:and
                                       [:= :media/library_id library-id]
                                       [:= :media/item_kind (name kind)]])))
               (map row->media)
               (catalog/enrich-media-with-timestamps this)))
      (throw (ex-info (format "library not found: %s" library-name) {}))))

  ;; NEW: Convenience function for filler items
  (get-filler-items [this library-name]
    (catalog/get-media-by-kind this library-name :filler))

  (search-media-by-library-id [this library-id opts]
    (->> (sql:fetch! executor (sql:search-top-level-media library-id opts))
         (map row->media)
         (catalog/enrich-media-with-timestamps this)))

  ;; NEW: Count media by kind
  (count-media-by-kind [_ library-name]
    (if-let [library-id (some-> (sql:fetch! executor (sql:get-library-id library-name))
                                first
                                :library/id)]
      (let [counts (sql:fetch! executor
                               (-> (select :media/item_kind (:%count.* :count))
                                   (from :media)
                                   (where [:= :media/library_id library-id])
                                   (group-by :media/item_kind)))]
        (reduce (fn [acc {:keys [media/item_kind count]}]
                  (assoc acc (keyword item_kind) count))
                {}
                counts))
      (throw (ex-info (format "library not found: %s" library-name) {}))))

  (get-tags [_]
    (map (comp keyword :tag/name) (sql:fetch! executor (sql:get-tags))))

  ;; DEPRECATED: Hardcoded channel list. Channels are dimensions now.
  ;; See protocol note in media/catalog.clj.
  (get-channels [_]
    (map (fn [{:keys [channel/name channel/full_name channel/id channel/description]}]
           {:name name :full-name full_name :id id :description description})
         (sql:fetch! executor (sql:get-channels))))

  ;; DEPRECATED: Hardcoded genre list. Genres are dimensions now.
  ;; See protocol note in media/catalog.clj.
  (get-genres [_]
    (map (comp keyword :genre/name) (sql:fetch! executor (sql:get-genres))))

  (get-tag-samples [_]
    (map (fn [{:keys [media_tags/tag usage_count example_titles]}]
           {:tag            tag
            :usage_count    usage_count
            :example_titles (pgarray->vec example_titles)})
         (sql:fetch! executor (sql:get-tag-samples))))

  (get-media-by-id [this media-id]
    (when-let [media (first (sql:fetch! executor (-> (sql:get-media)
                                                     (where [:= :media/id media-id]))))]
      (first (catalog/enrich-media-with-timestamps this [(row->media media)]))))

  (add-media-tags! [_ media-id tags]
    (sql:exec-with-tx! executor
                       [(sql:insert-tags tags)
                        (sql:insert-media-tags media-id tags)]))

  (set-media-tags! [_ media-id tags]
    ;; "set" replaces the whole tag set: clear every existing association for
    ;; this item first (delete-media-tags! only removes the tags passed to it,
    ;; which would leave the previous tags in place), then insert the new set.
    (sql:exec-with-tx! executor
                       [(-> (delete-from :media_tags) (where [:= :media_id media-id]))
                        (sql:insert-tags tags)
                        (sql:insert-media-tags media-id tags)]))

  (delete-media-tags! [_ media-id tags]
    (when (seq tags)
      (sql:exec! executor (sql:delete-media-tags! media-id tags))))

  (get-media-tags [_ media-id]
    (map (comp keyword :media_tags/tag)
         (sql:fetch! executor (sql:get-media-tags media-id))))

  (get-media-process-timestamps [_ {:keys [::media/id]}]
    (map (fn [{:keys [media_process_timestamp/process
                      media_process_timestamp/last_run_at]}]
           {::media/process-name (keyword "process" process)
            ::media/last-run     (.toInstant last_run_at)})
         (sql:fetch! executor (sql:get-media-processes-by-id id))))

  ;; DEPRECATED: Hardcoded channel update. Channels are dimensions now.
  ;; See protocol note in media/catalog.clj.
  (update-channels! [_ channels]
    (sql:exec! executor (sql:insert-channels channels)))

  (update-libraries! [_ libraries]
    (sql:exec! executor (sql:insert-libraries libraries)))

  ;; DEPRECATED: Hardcoded channel assignment. Channels are dimensions now.
  ;; See protocol note in media/catalog.clj.
  (add-media-channels! [_ media-id channels]
    (sql:exec! executor (sql:insert-media-channels media-id channels)))

  ;; DEPRECATED: Hardcoded genre assignment. Genres are dimensions now.
  ;; See protocol note in media/catalog.clj.
  (add-media-genres! [_ media-id genres]
    ;; Insert the genre vocabulary rows first: media_genres.genre is an FK to
    ;; genre(name), so the referenced genres must exist before the association.
    (sql:exec-with-tx! executor
                       [(sql:insert-genres genres)
                        (sql:insert-media-genres media-id genres)]))

  ;; DEPRECATED: Hardcoded channel filter. Channels are dimensions now.
  ;; See protocol note in media/catalog.clj.
  (get-media-by-channel [this channel]
    (->> (sql:fetch! executor
                     (-> (sql:get-media)
                         (where [:= :media_channels/channel (name channel)])))
         (map row->media)
         (catalog/enrich-media-with-timestamps this)))

  (get-media-by-tag [this tag]
    (->> (sql:fetch! executor
                     (-> (sql:get-media)
                         (where [:= :media_tags/tag (name tag)])))
         (map row->media)
         (catalog/enrich-media-with-timestamps this)))

  ;; DEPRECATED: Hardcoded genre filter. Genres are dimensions now.
  ;; See protocol note in media/catalog.clj.
  (get-media-by-genre [this genre]
    (->> (sql:fetch! executor
                     (-> (sql:get-media)
                         (where [:= :media_genres/genre (name genre)])))
         (map row->media)
         (catalog/enrich-media-with-timestamps this)))

  (get-media-by-category-value [this category value]
    (->> (sql:fetch! executor
                     (-> (select :media.id
                                 :media.name
                                 :media.overview
                                 :media.community_rating
                                 :media.critic_rating
                                 :media.media_type
                                 :media.production_year
                                 :media.premiere
                                 :media.subtitles
                                 :media.kid_friendly
                                 :media.library_id
                                 :media.parent_id
                                 :media.season_number
                                 :media.episode_number
                                 [[:array_agg [:distinct :media_tags.tag]]
                                  :tags]
                                 [[:array_agg [:distinct :media_channels.channel]]
                                  :channels]
                                 [[:array_agg [:distinct :media_genres.genre]]
                                  :genres]
                                 [[:array_agg [:distinct :media_taglines.tagline]]
                                  :taglines])
                         (from :media)
                         (join :media_categorization
                               [:= :media.id :media_categorization/media_id])
                         (left-join :media_tags
                                    [:= :media.id :media_tags.media_id])
                         (left-join :media_channels
                                    [:= :media.id :media_channels.media_id])
                         (left-join :media_genres
                                    [:= :media.id :media_genres.media_id])
                         (left-join :media_taglines
                                    [:= :media.id :media_taglines.media_id])
                         (where [:and
                                 [:= :media_categorization/category (name category)]
                                 [:= :media_categorization/category_value (name value)]])
                         (group-by :media.id)))
         (map row->media)
         (catalog/enrich-media-with-timestamps this)))

  (add-media-taglines! [_ media-id taglines]
    (sql:exec! executor (sql:insert-media-taglines media-id taglines)))

  (delete-tag! [_ tag]
    (sql:exec! executor (sql:delete-tag tag)))

  (rename-tag! [_ tag new-tag]
    (if (tag-exists? executor new-tag)
      (do (sql:exec! executor (sql:retag-media tag new-tag))
          (sql:exec! executor (sql:delete-tag tag)))
      (sql:exec! executor (sql:rename-tag tag new-tag))))

  (batch-rename-tags! [_ tag-pairs]
    ;; Perform all tag renames in a single transaction to avoid N+1 queries
    (let [queries (mapcat (fn [[tag new-tag]]
                            (if (tag-exists? executor new-tag)
                              [(sql:retag-media tag new-tag)
                               (sql:delete-tag tag)]
                              [(sql:rename-tag tag new-tag)]))
                          tag-pairs)]
      (sql:exec-with-tx! executor queries)))

  (update-process-timestamp! [_ media-id process]
    (sql:exec! executor (sql:update-process-timestamp media-id process)))

  (delete-process-timestamp! [_ media-id process]
    (sql:exec! executor (sql:delete-process-timestamp media-id process)))

  (delete-library-process-timestamps! [_ library process]
    (if-let [library-id (some-> (sql:fetch! executor (sql:get-library-id library))
                                first
                                :library/id)]
      (sql:exec! executor (sql:delete-library-process-timestamps library-id process))
      (throw (ex-info (format "library not found: %s" (name library)) {}))))

  (close-catalog! [_] (executor/close! executor))

  (get-media-category-values [_ media-id category]
    (map (comp keyword :media_categorization/category_value)
         (sql:fetch! executor (sql:get-media-category-values media-id category))))

  (add-media-category-value! [_ media-id category value rationale]
    (sql:exec! executor (sql:add-media-category-values! media-id category
                                                        [{::media/category-value value
                                                          ::media/rationale      rationale}])))

  (add-media-category-values! [_ media-id category values]
    (sql:exec! executor (sql:add-media-category-values! media-id category values)))

  (set-media-category-values! [_ media-id category values]
    (sql:exec-with-tx! executor
                       [(sql:delete-media-category-values! media-id category)
                        (sql:add-media-category-values! media-id category values)]))

  (get-media-categories [_ media-id]
    (into {}
          (map (fn [{:keys [media_categorization/category values] :as row}]
                 (when (nil? category)
                   (log/error "Null category in media categories query"
                             {:media-id media-id
                              :row row
                              :keys (keys row)})
                   (throw (ex-info "Null category value in media_categorization query result"
                                  {:media-id media-id
                                   :row row
                                   :available-keys (keys row)})))
                 (let [value-vec (->> values
                                     pgarray->vec
                                     vec)]
                   (when (some nil? value-vec)
                     (throw (ex-info "Null values found in category values array"
                                    {:media-id media-id
                                     :category category
                                     :values value-vec})))
                   [(keyword category) (mapv keyword value-vec)])))
          (sql:fetch! executor (sql:get-media-categories media-id))))

  (delete-media-category-value! [_ media-id category value]
    (sql:exec! executor (sql:delete-media-category-value! media-id category value)))

  (delete-media-category-values! [_ media-id category]
    (sql:exec! executor (sql:delete-media-category-values! media-id category)))

  (purge-category-value! [_ category value]
    (sql:exec! executor (sql:purge-category-value! category value)))

  (get-all-dimensions [_]
    (map (fn [{:keys [media_categorization/category value_count]}]
           {:name (keyword category)
            :value-count value_count})
         (sql:fetch! executor (sql:get-all-dimensions))))

  (get-dimension-values [_ dimension]
    (map (fn [{:keys [media_categorization/category_value usage_count]}]
           {:value (keyword category_value)
            :usage-count usage_count})
         (sql:fetch! executor (sql:get-dimension-values dimension))))

  (get-episodes-by-series [this series-id]
    (->> (sql:fetch! executor (sql:get-episodes-by-series series-id))
         (map row->media)
         (catalog/enrich-media-with-timestamps this)))

  (get-episode [_ series-id season-number episode-number]
    (some->> (sql:fetch! executor (sql:get-episode series-id season-number episode-number))
             first
             row->media))

  (get-effective-tags [self media-id]
    (let [own-tags (catalog/get-media-tags self media-id)
          media-row (first (map row->media
                                (sql:fetch! executor
                                            (-> (sql:get-media)
                                                (where [:= :media/id media-id])))))
          parent-tags (when-let [pid (::media/parent-id media-row)]
                        (catalog/get-media-tags self pid))]
      (vec (distinct (concat own-tags parent-tags)))))

  (get-effective-categories [self media-id]
    (let [own-cats (catalog/get-media-categories self media-id)
          media-row (first (map row->media
                                (sql:fetch! executor
                                            (-> (sql:get-media)
                                                (where [:= :media/id media-id])))))
          parent-cats (when-let [pid (::media/parent-id media-row)]
                        (catalog/get-media-categories self pid))]
      (if parent-cats
        ;; Episode: inherit parent categories, overridden by own categories per dimension
        (merge parent-cats own-cats)
        ;; Non-episode: just own categories
        own-cats)))

  (get-media-context [_ media-id]
    (some-> (sql:fetch! executor (sql:get-media-context media-id))
            first
            context-row->map))

  (set-media-context! [_ media-id context]
    (sql:exec! executor (sql:upsert-media-context media-id context)))

  (delete-media-context! [_ media-id]
    (sql:exec! executor (sql:delete-media-context media-id)))

  (get-library-id [_ library]
    (some-> (sql:fetch! executor (sql:get-library-id library))
            first
            :library/id))

  (enrich-media-with-timestamps [this media]
    "Add process timestamps to media metadata."
    (if (sequential? media)
      (map (fn [m]
             (let [id (::media/id m)
                   timestamps (catalog/get-media-process-timestamps this {::media/id id})]
               (assoc m ::media/process-timestamps timestamps)))
           media)
      (let [id (::media/id media)
            timestamps (catalog/get-media-process-timestamps this {::media/id id})]
        (assoc media ::media/process-timestamps timestamps)))))

(defmethod catalog/initialize-catalog! :postgresql
  [{:keys [host port user password database worker-count queue-size]
    :or {worker-count 5
         queue-size   30}}]
  (let [db (jdbc/get-datasource {:dbtype   "postgresql"
                                 :dbname   database
                                 :user     user
                                 :password password
                                 :host     host
                                 :port     port})
        executor (executor/create-executor db :worker-count worker-count :queue-size queue-size)]
    (->SqlCatalog executor)))
