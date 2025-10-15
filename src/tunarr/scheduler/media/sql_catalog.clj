(ns tunarr.scheduler.media.sql-catalog
  (:require [tunarr.scheduler.media.catalog :as catalog]
            [honey.sql :as sql]
            [honey.sql.helpers :refer [select from join where insert-into update values set delete-from returning on-conflict do-nothing columns] :as honey]
            [next.jdbc :as jdbc]
            [tunarr.scheduler.media :as media]
            [clojure.stacktrace :refer [print-stack-trace]]))

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

(defn sql:exec!
  "Execute the given SQL statements in a transaction"
  [store verbose & sqls]
  (letfn [(log! [sql]
            (when verbose
              (println (str "executing: " sql)))
            sql)]
    (try
      (jdbc/with-transaction [tx (jdbc/get-connection store)]
        (doseq [sql sqls]
          (jdbc/execute! tx (log! (sql/format sql)))))
      (catch Exception e
        (when (:verbose store)
          (println (capture-stack-trace e)))
        (throw e)))))

(defn sql:fetch!  
  "Fetch results for the given SQL query" 
  [store verbose sql]
  (letfn [(log! [sql]
            (when (:verbose store)
              (println (str "fetching: " sql))
              sql))]
    (try
      (jdbc/execute! (:datasource store) (log! (sql/format sql)))
      (catch Exception e
        (when (:verbose store)
          (println (capture-stack-trace e)))
        (throw e)))))

(defn sql:insert-tag
  [tag]
  (-> (insert-into :tag)
      (values {:name tag})
      (on-conflict [:name]) (do-nothing)))

(defn sql:insert-media-tag
  [media-id tag]
  (-> (insert-into :media_tags)
      (values {:tag tag :media_id media-id})
      (on-conflict [:tag :media_id]) (do-nothing)))

(defn sql:insert-media-tags
  [media-id tags]
  (concat (map sql:insert-tag tags)
          (map (partial sql:insert-media-tag media-id) tags)))

(defn sql:insert-genre
  [genre]
  (-> (insert-into :genre)
      (values {:name genre})
      (on-conflict [:name] (do-nothing))))

(defn sql:insert-media-genre
  [media-id genre]
  (-> (insert-into :media_genres)
      (values {:genre genre :media_id media-id})
      (on-conflict [:genre :media_id]) (do-nothing)))

(defn sql:insert-media-genres
  [media-id genres]
  (concat (map sql:insert-genre genres)
          (map (partial sql:insert-media-genre media-id) genres)))

(defn sql:insert-channel
  [channel]
  (-> (insert-into :channel)
      (values {:name channel})
      (on-conflict [:name] (do-nothing))))

(defn sql:insert-media-channel
  [media-id channel]
  (-> (insert-into :media_channels)
      (values {:channel channel :media_id media-id})
      (on-conflict [:channel :media_id]) (do-nothing)))

(defn sql:insert-media-channels
  [media-id channels]
  (concat (map sql:insert-channel channels)
          (map (partial sql:insert-media-channel media-id) channels)))

(defn sql:add-media
  [{:keys [::media/id
           ::media/tags
           ::media/channels
           ::media/genres]
    :as media}]
  (let [media-keys [::media/id
                    ::media/name
                    ::media/library-id
                    ::media/overview
                    ::media/community-rating
                    ::media/critic-rating
                    ::media/type
                    ::media/production-year
                    ::media/premiere
                    ::media/subtitles
                    ::media/kid-friendly]]
    (concat [(-> (insert-into :media)
                 (values (update-keys (select-keys media-keys media)
                                      field-map)))]
            (map (partial sql:insert-media-tags id) tags)
            (map (partial sql:insert-media-genres id) genres)
            (map (partial sql:insert-media-channels id) channels))))

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
              [[:array_agg :distinct :media_tags.tag] :tags]
              [[:array_agg :distinct :media_channels.channel] :channels]
              [[:array_agg :distinct :media_genres.genre] :genres])
      (from :media)
      (join :media_tags [:= :media.id :media_tags.media_id]
            :media_channels [:= :media.id :media_channels.media_id]
            :media_genres [:= :media.id :media_genres.media_id])))

(defrecord SqlCatalog [store verbose]
  catalog/Catalog
  (add-media [_ media]
    (sql:exec! store verbose (sql:add-media media)))
  (get-media [_]
    (sql:fetch! store verbose (sql:get-media)))
  (get-media-by-library-id [_ library-id]
    (sql:fetch! store verbose
                (-> (sql:get-media)
                    (where [:= :media.library_id library-id]))))
  (get-media-by-id [_ media-id]
    (sql:fetch! store verbose
                (-> (sql:get-media)
                    (where [:= :media.id media-id]))))
  (add-media-tags [_ media-id tags]
    (sql:exec! store verbose (sql:insert-media-tags media-id tags)))
  (add-media-channels [_ media-id channels]
    (sql:exec! store verbose (sql:insert-media-channels media-id channels)))
  (add-media-genres [_ media-id genres]
    (sql:exec! store verbose (sql:insert-media-channels media-id genres)))
  (get-media-by-channel [_ channel]
    (sql:fetch! store verbose
                (-> (sql:get-media)
                    (where [:= :media_channels channel]))))
  (get-media-by-tag [_ tag]
    (sql:fetch! store verbose
                (-> (sql:get-media)
                    (where [:= :media_tags tag]))))
  (get-media-by-genre [_ genre]
    (sql:fetch! store verbose
                (-> (sql:get-media)
                    (where [:= :media_genres genre]))))
  (close! [_]))

(defmethod catalog/initialize-catalog :postgresql
  [{:keys [host port user password database verbose]
    :or   {verbose false}}]
  (let [db (jdbc/get-datasource {:dbtype   "postgresql"
                                 :dbname   database
                                 :user     user
                                 :password password
                                 :host     host
                                 :port     port})]
    (->SqlCatalog db verbose)))
