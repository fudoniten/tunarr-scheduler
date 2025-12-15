(ns tunarr.scheduler.media.sql-catalog
  (:require [tunarr.scheduler.media :as media]
            [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.sql.executor :as executor]
            
            [clojure.stacktrace :refer [print-stack-trace]]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :refer [instrument]]
            
            [honey.sql.helpers :refer [select from where insert-into values on-conflict do-nothing left-join group-by columns do-update-set delete-from] :as sql]
            [next.jdbc :as jdbc]))

(def field-map
  {::media/name             :name
   ::media/overview         :overview
   ::media/community-rating :community_rating
   ::media/critic-rating    :critic_rating
   ::media/rating           :rating
   ::media/id               :id
   ::media/type             :media_type
   ::media/production-year  :production_year
   ::media/subtitles?       :subtitles
   ::media/premiere         :premiere
   ::media/library-id       :library_id
   ::media/kid-friendly?    :kid_friendly})

(defn capture-stack-trace
  [e]
  (with-out-str (print-stack-trace e)))

(defn sql:exec-with-tx!
  [executor queries]
  (deref (executor/exec-with-tx! executor queries)))

(defn sql:exec!
  [executor query]
  (deref (executor/exec! executor query)))

(defn sql:fetch!
  [executor query]
  (deref (executor/fetch! executor query)))

(defn media->row
  "Rename the media map keys to match the SQL schema."
  [media]
  (reduce (fn [acc [media-key column]]
            (if (contains? media media-key)
              (assoc acc column (get media media-key))
              acc))
          {}
          field-map))

(defn sql:insert-tags
  [tags]
  (-> (insert-into :tag)
      (columns :name)
      (values (map (comp vector name) tags))
      (on-conflict :name) (do-nothing)))

(defn sql:delete-media-tags!
  [media-id tags]
  (-> (delete-from :tags)
      (where [:= :media_id media-id]
             [:in :tag (map name tags)])))

(defn sql:get-tags
  []
  (-> (select :name) (from :tag)))

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
      (where [:= :name tag])))

(defn sql:rename-tag
  [tag new-tag]
  (-> (sql/update :tag)
      (sql/set {:name new-tag})
      (where [:= :name tag])))

(defn sql:get-tag
  [tag]
  (-> (select :name)
      (from :tag)
      (where [:= :name tag])))

(defn sql:get-media-tags
  [media-id]
  (-> (select :tag)
      (from :media_tags)
      (where [:= :media_id media-id])))

(defn sql:retag-media
  [tag new-tag]
  (-> (sql/update :media_tags)
      (sql/set {:name new-tag})
      (where [:= :name tag])
      (on-conflict) (do-nothing)))

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
             [:= :category category]
             [:= :category_value value])))

(defn sql:delete-media-category-values! [media-id category]
  (-> (delete-from :media_categorization)
      (where [:= :media_id media-id]
             [:= :category category])))

(defn tag-exists?
  [executor tag]
  (let [[status result] (sql:fetch! executor (sql:get-tag tag))]
    (if (= status :ok)
      (= tag (:tag/name result))
      (throw result))))

(defn sql:update-process-timestamp
  [media-id process]
  (-> (insert-into :media_process_timestamp)
      (columns :media_id :process :last_run_at)
      (values [[media-id process :now]])
      (on-conflict :media_id :process)
      (do-update-set {:last_run_at [:excluded :last_run_at]})))

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
    (concat [(sql:insert-tags tags)
             (sql:insert-genres genres)]
            [(-> (insert-into :media)
                 (values [row]))]
            [(sql:insert-media-tags id tags)
             (sql:insert-media-genres id genres)
             (sql:insert-media-channels id channels)
             (sql:insert-media-taglines id taglines)])))

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
               :taglines]
              [[:json_agg [:distinct
                           [:json_build_object
                            "process" :media_process_timestamp.process
                            "last_run_at" :media_process_timestamp.last_run_at]]]
               :process-timestamps])
      (from :media)
      (left-join :media_tags
                 [:= :media.id :media_tags.media_id])
      (left-join :media_channels
                 [:= :media.id :media_channels.media_id])
      (left-join :media_genres
                 [:= :media.id :media_genres.media_id])
      (left-join :media_taglines
                 [:= :media.id :media_taglines.media_id])
      (left-join :media_process_timestamp
                 [:= :media.id :media_process_timestamp.media_id])
      (group-by :media.id)))

(defrecord SqlCatalog [executor]
  catalog/Catalog
  (add-media! [_ media]
    (sql:exec-with-tx! executor (sql:add-media media)))

  (get-media [_]
    (sql:fetch! executor (sql:get-media)))

  (get-media-by-library-id [_ library-id]
    (sql:fetch! executor (-> (sql:get-media)
                             (where [:= :media.library_id library-id]))))

  (get-tags [_]
    (let [[status tags] (sql:fetch! executor (sql:get-tags))]
      (if-not (= status :ok)
        (throw tags)
        (map (comp keyword :tag/name) tags))))

  (get-media-by-id [_ media-id]
    (sql:fetch! executor (-> (sql:get-media)
                             (where [:= :media.id media-id]))))

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
    (sql:fetch! executor (sql:get-media-tags media-id)))

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
                    (where [:= :media_channels.channel channel]))))

  (get-media-by-tag [_ tag]
    (sql:fetch! executor
                (-> (sql:get-media)
                    (where [:= :media_tags.tag tag]))))

  (get-media-by-genre [_ genre]
    (sql:fetch! executor
                (-> (sql:get-media)
                    (where [:= :media_genres.genre genre]))))

  (add-media-taglines! [_ media-id taglines]
    (sql:exec! executor (sql:insert-media-taglines media-id taglines)))

  (delete-tag! [_ tag]
    (sql:exec! executor (sql:delete-tag tag)))

  (rename-tag! [_ tag new-tag]
    (if (tag-exists? executor new-tag)
      (sql:exec-with-tx! executor [(sql:retag-media tag new-tag)
                                   (sql:delete-tag tag)])
      (sql:exec! executor (sql:rename-tag tag new-tag))))

  (update-process-timestamp! [_ media-id process]
    (sql:exec! executor (sql:update-process-timestamp media-id process)))

  (close-catalog! [_] (executor/close! executor))

  (get-media-category-values [_ media-id category]
    (sql:exec! executor (sql:get-media-category-values media-id category)))

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
          (map (fn [[cat vals]] [(keyword cat) (map keyword vals)]))
          (sql:exec! executor (sql:get-media-categories media-id))))

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
