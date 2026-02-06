(ns tunarr.scheduler.media.sql-catalog
  (:require [tunarr.scheduler.media :as media]
            [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.sql.executor :as executor]

            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :refer [instrument]]

            [honey.sql.helpers :refer [select from where insert-into values on-conflict do-nothing left-join group-by columns do-update-set delete-from order-by] :as sql]
            [next.jdbc :as jdbc]
            [taoensso.timbre :as log])
  (:import java.sql.Array))

(def field-map
  {::media/name             :media/name
   ::media/overview         :media/overview
   ::media/community-rating :media/community_rating
   ::media/critic-rating    :media/critic_rating
   ::media/rating           :media/rating
   ::media/id               :media/id
   ::media/type             :media/media_type
   ::media/production-year  :media/production_year
   ::media/subtitles?       :media/subtitles
   ::media/premiere         :media/premiere
   ::media/library-id       :media/library_id
   ::media/kid-friendly?    :media/kid_friendly
   ::media/tags             :tags
   ::media/channel-names    :channels
   ::media/genres           :genres
   ::media/taglines         :taglines})

(defn pgarray->vec [o]
  (if (instance? Array o)
    (let [arr (.getArray ^Array o)]
      (vec (seq arr)))
    o))

(defn map-over [f] (fn [o] (map f o)))

(def field-transforms
  {:media/name              identity
   :media/overview          identity
   :media/community_rating  identity
   :media/critic_rating     identity
   :media/rating            identity
   :media/id                identity
   :media/media_type        keyword
   :media/production_year   identity
   :media/subtitles         identity
   :media/premiere          identity
   :media/library_id        identity
   :media/kid_friendly      identity
   :tags                    (comp (partial map keyword) pgarray->vec)
   :channels                (comp (partial map keyword) pgarray->vec)
   :genres                  (comp (partial map keyword) pgarray->vec)
   :taglines                pgarray->vec})

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
  (-> (select [[:media_tags.tag :tag]]
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
      (where [:= :name (name library)])))

(defn sql:insert-media-tags
  [media-id tags]
  (-> (insert-into :media_tags)
      (columns :media_id :tag)
      (values (map (fn [tag] [media-id (name tag)]) tags))
      (on-conflict :tag :media_id) (do-nothing)))

(defn sql:insert-genres
  [genres]
  (-> (insert-into :genre)
      (columns :name)
      (values (map (comp vector name) genres))
      (on-conflict :name)
      (do-nothing)))

(defn sql:insert-media-genres
  [media-id genres]
  (-> (insert-into :media_genres)
      (columns :media_id :genre)
      (values (map (fn [genre] [media-id (name genre)]) genres))
      (on-conflict :media_id :genre) (do-nothing)))

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
      (columns :name :id)
      (values (into [] (map (fn [[k v]] [(name k) v])) libraries))
      (on-conflict :id) (do-update-set :id :name)))

(s/fdef sql:insert-channels
  :args (s/cat :channels ::media/channel-descriptions))

(instrument 'sql:insert-channels)

(defn sql:insert-media-channels
  [media-id channels]
  (-> (insert-into :media_channels)
      (columns :media_id :channel)
      (values (map (fn [channel] [media-id channel]) channels))
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
  [media-id category values]
  (-> (insert-into :media_categorization)
      (columns :media_id :category :category_value)
      (values (map (fn [value] [media-id category value])
                   values))
      (on-conflict :media_id :category :category_value) (do-nothing)))

(defn sql:get-media-categories [media-id]
  (-> (select :category
              [[:array_agg [:distinct :media_categorization.category_value]] :values])
      (from :media_categorization)
      (where [:= :media_id media-id])))

(defn sql:delete-media-category-value! [media-id category value]
  (-> (delete-from :media_categorization)
      (where [:= :media_id media-id]
             [:= :category (name category)]
             [:= :category_value (name value)])))

(defn sql:delete-media-category-values! [media-id category]
  (-> (delete-from :media_categorization)
      (where [:= :media_id media-id]
             [:= :category (name category)])))

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

(defn optional [pred lst]
  (if pred lst []))

(defn sql:add-media
  [{:keys [::media/id
           ::media/tags
           ::media/channels
           ::media/genres
           ::media/taglines]
    :as media}]
  (let [row (-> media
                (media->row)
                (update :media_type name))]
    (concat (optional (seq tags) [(sql:insert-tags tags)])
            (optional (seq genres) [(sql:insert-genres genres)])
            [(-> (insert-into :media) (values [row])
                 (on-conflict :id) (do-nothing))]
            (optional (seq tags) [(sql:insert-media-tags id tags)])
            (optional (seq genres) [(sql:insert-media-genres id genres)])
            (optional (seq channels) [(sql:insert-media-channels id channels)])
            (optional (seq taglines) [(sql:insert-media-taglines id taglines)]))))

(defn sql:add-media-batch
  "Generate SQL queries to batch insert multiple media items.

  This is more efficient than calling sql:add-media for each item individually,
  as it groups inserts by type and reduces the number of transactions."
  [media-items]
  (let [;; Convert all media to rows
        rows (mapv (fn [media]
                     (-> media
                         (media->row)
                         (update :media_type name)))
                   media-items)
        ;; Collect all tags, genres, channels, taglines across all media
        all-tags (distinct (mapcat ::media/tags media-items))
        all-genres (distinct (mapcat ::media/genres media-items))
        ;; Build tag/genre/channel/tagline associations per media
        tag-associations (mapcat (fn [{:keys [::media/id ::media/tags]}]
                                   (map (fn [tag] [id (name tag)]) tags))
                                 media-items)
        genre-associations (mapcat (fn [{:keys [::media/id ::media/genres]}]
                                     (map (fn [genre] [id (name genre)]) genres))
                                   media-items)
        channel-associations (mapcat (fn [{:keys [::media/id ::media/channels]}]
                                       (map (fn [channel] [id channel]) channels))
                                     media-items)
        tagline-associations (mapcat (fn [{:keys [::media/id ::media/taglines]}]
                                       (map (fn [tagline] [id tagline]) taglines))
                                     media-items)]
    (concat
     ;; Insert all unique tags first
     (optional (seq all-tags) [(sql:insert-tags all-tags)])
     ;; Insert all unique genres
     (optional (seq all-genres) [(sql:insert-genres all-genres)])
     ;; Batch insert all media rows
     [(-> (insert-into :media)
          (values rows)
          (on-conflict :id)
          (do-nothing))]
     ;; Batch insert tag associations
     (optional (seq tag-associations)
               [(-> (insert-into :media_tags)
                    (columns :media_id :tag)
                    (values tag-associations)
                    (on-conflict :tag :media_id)
                    (do-nothing))])
     ;; Batch insert genre associations
     (optional (seq genre-associations)
               [(-> (insert-into :media_genres)
                    (columns :media_id :genre)
                    (values genre-associations)
                    (on-conflict :media_id :genre)
                    (do-nothing))])
     ;; Batch insert channel associations
     (optional (seq channel-associations)
               [(-> (insert-into :media_channels)
                    (columns :media_id :channel)
                    (values channel-associations)
                    (on-conflict :media_id :channel)
                    (do-nothing))])
     ;; Batch insert tagline associations
     (optional (seq tagline-associations)
               [(-> (insert-into :media_taglines)
                    (columns :media_id :tagline)
                    (values tagline-associations)
                    (on-conflict :media_id :tagline)
                    (do-nothing))]))))

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

(defrecord SqlCatalog [executor]
  catalog/Catalog
  (add-media! [_ media]
    (sql:exec-with-tx! executor (sql:add-media media)))

  (add-media-batch! [_ media-items]
    (sql:exec-with-tx! executor (sql:add-media-batch media-items)))

  (get-media [_]
    (sql:fetch! executor (sql:get-media)))

  (get-media-by-library-id [_ library-id]
    (->> (sql:fetch! executor
                     (-> (sql:get-media)
                         (where [:= :media/library_id library-id])))
         (map row->media)))

  (get-media-by-library [self library]
    (if-let [library-id (some-> (sql:fetch! executor (sql:get-library-id library))
                                first
                                :library/id)]
      (do (log/info (format "getting media for library id %s" library-id))
          (catalog/get-media-by-library-id self library-id))
      (throw (ex-info (format "library not found: %s" (name library)) {}))))

  (get-tags [_]
    (map (comp keyword :tag/name) (sql:fetch! executor (sql:get-tags))))

  (get-tag-samples [_]
    (map (fn [{:keys [tag usage_count example_titles]}]
           {:tag            tag
            :usage_count    usage_count
            :example_titles (pgarray->vec example_titles)})
         (sql:fetch! executor (sql:get-tag-samples))))

  (get-media-by-id [_ media-id]
    (sql:fetch! executor (-> (sql:get-media)
                             (where [:= :media/id media-id]))))

  (add-media-tags! [_ media-id tags]
    (sql:exec-with-tx! executor
                       [(sql:insert-tags tags)
                        (sql:insert-media-tags media-id tags)]))

  (set-media-tags! [_ media-id tags]
    (sql:exec-with-tx! executor
                       [(sql:delete-media-tags! media-id tags)
                        (sql:insert-tags tags)
                        (sql:insert-media-tags media-id tags)]))

  (get-media-tags [_ media-id]
    (map (comp keyword :media_tags/tag)
         (sql:fetch! executor (sql:get-media-tags media-id))))

  (get-media-process-timestamps [_ {:keys [::media/id]}]
    (map (fn [{:keys [media_process_timestamp/process
                     media_process_timestamp/last_run_at]}]
           {:media/process-name (keyword "process" process)
            :media/last-run     (.toInstant last_run_at)})
         (sql:fetch! executor (sql:get-media-processes-by-id id))))

  (update-channels! [_ channels]
    (sql:exec! executor (sql:insert-channels channels)))

  (update-libraries! [_ libraries]
    (sql:exec! executor (sql:insert-libraries libraries)))

  (add-media-channels! [_ media-id channels]
    (sql:exec! executor (sql:insert-media-channels media-id channels)))

  (add-media-genres! [_ media-id genres]
    (sql:exec! executor (sql:insert-media-genres media-id genres)))

  (get-media-by-channel [_ channel]
    (sql:fetch! executor
                (-> (sql:get-media)
                    (where [:= :media_channels/channel channel]))))

  (get-media-by-tag [_ tag]
    (sql:fetch! executor
                (-> (sql:get-media)
                    (where [:= :media_tags/tag tag]))))

  (get-media-by-genre [_ genre]
    (sql:fetch! executor
                (-> (sql:get-media)
                    (where [:= :media_genres/genre genre]))))

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

  (close-catalog! [_] (executor/close! executor))

  (get-media-category-values [_ media-id category]
    (map (comp keyword :media_categorization/category_value)
         (sql:fetch! executor (sql:get-media-category-values media-id category))))

  (add-media-category-value! [_ media-id category value]
    (sql:exec! executor (sql:add-media-category-values! media-id category [value])))

  (add-media-category-values! [_ media-id category values]
    (sql:exec! executor (sql:add-media-category-values! media-id category values)))

  (set-media-category-values! [_ media-id category values]
    (sql:exec-with-tx! executor
                       [(sql:delete-media-category-values! media-id category)
                        (sql:add-media-category-values! media-id category values)]))

  (get-media-categories [_ media-id]
    (into {}
          (map (fn [{:keys [category values]}]
                 [(keyword category) (map keyword values)]))
          (sql:fetch! executor (sql:get-media-categories media-id))))

  (delete-media-category-value! [_ media-id category value]
    (sql:exec! executor (sql:delete-media-category-value! media-id category value)))

  (delete-media-category-values! [_ media-id category]
    (sql:exec! executor (sql:delete-media-category-values! media-id category))))

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
