(ns tunarr.scheduler.media.sql-catalog
  (:require [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.sql.executor :as executor]
            [honey.sql.helpers :refer [select from where insert-into values on-conflict do-nothing left-join group-by columns do-update-set]]
            [next.jdbc :as jdbc]
            [taoensso.timbre :as log]
            [tunarr.scheduler.media :as media]
            [clojure.stacktrace :refer [print-stack-trace]]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :refer [instrument]]))

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

#_(defn sql:exec!
    "Execute the given SQL statements in a transaction"
    [store verbose & sqls]
    (letfn [(log! [sql]
              (when verbose
                (log/info (str "executing: " sql)))
              sql)]
      (try
        (jdbc/with-transaction [tx (jdbc/get-connection store)]
          (doseq [statement (->> sqls
                                 (mapcat #(if (sequential? %) % [%]))
                                 (remove nil?))]
            (jdbc/execute! tx (log! (sql/format statement)))))
        (catch Exception e
          (when verbose
            (println (capture-stack-trace e)))
          (throw e)))))

#_(defn sql:fetch!
  "Fetch results for the given SQL query"
  [store verbose sql]
  (letfn [(log! [sql]
            (when verbose
              (println (str "fetching: " sql)))
            sql)]
    (try
      (jdbc/execute! store (log! (sql/format sql)))
      (catch Exception e
        (when verbose
          (println (capture-stack-trace e)))
        (throw e)))))

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

(defn sql:insert-taglines
  [media-id taglines]
  (-> (insert-into :media_taglines)
      (columns :media_id :tagline)
      (values (map (fn [tagline] [media-id tagline])
                   taglines))
      (on-conflict :media_id :tagline (do-nothing))))

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
             (sql:insert-taglines id taglines)])))

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
              [[:array_agg [:distinct :media_tags.tag]] :tags]
              [[:array_agg [:distinct :media_channels.channel]] :channels]
              [[:array_agg [:distinct :media_genres.genre]] :genres]
              [[:array_agg [:distinct :media_taglines.tagline] :taglines]])
      (from :media)
      (left-join :media_tags [:= :media.id :media_tags.media_id])
      (left-join :media_channels [:= :media.id :media_channels.media_id])
      (left-join :media_genres [:= :media.id :media_genres.media_id])
      (left-join :media_genres [:= :media.id :media_taglines.media_id])
      (group-by :media.id)))

(defrecord SqlCatalog [executor]
  catalog/Catalog
  (add-media [_ media]
    (sql:exec-with-tx! executor (sql:add-media media)))
  (get-media [_]
    (sql:fetch! executor (sql:get-media)))
  (get-media-by-library-id [_ library-id]
    (sql:fetch! executor (-> (sql:get-media)
                          (where [:= :media.library_id library-id]))))
  (get-media-by-id [_ media-id]
    (sql:fetch! executor (-> (sql:get-media)
                          (where [:= :media.id media-id]))))
  (add-media-tags [_ media-id tags]
    (sql:exec-with-tx! executor
                       [(sql:insert-tags tags)
                        (sql:insert-media-tags media-id tags)]))
  (update-channels [_ channels]
    (sql:exec! executor (sql:insert-channels channels)))
  (update-libraries [_ libraries]
    (sql:exec! executor (sql:insert-libraries libraries)))
  (add-media-channels [_ media-id channels]
    (sql:exec! executor (sql:insert-media-channels media-id channels)))
  (add-media-genres [_ media-id genres]
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
  (close-catalog! [_] (executor/close! executor)))

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
