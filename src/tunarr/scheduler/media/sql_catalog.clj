(ns tunarr.scheduler.media.sql-catalog
  (:require [tunarr.scheduler.media.catalog :as catalog]
            [honey.sql :as sql]
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

(defn sql:exec!
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

(defn sql:fetch!
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

(defn sql:insert-tag
  [tag]
  [(-> (insert-into :tag)
       (values [{:name tag}])
       (on-conflict :name) (do-nothing))])

(defn sql:insert-media-tag
  [media-id tag]
  [(-> (insert-into :media_tags)
       (values [{:tag tag :media_id media-id}])
       (on-conflict :tag :media_id) (do-nothing))])

(defn sql:insert-media-tags
  [media-id tags]
  [(let [tags (seq tags)]
     (if tags
       (concat (map sql:insert-tag tags)
               (map (partial sql:insert-media-tag media-id) tags))
       ()))])

(defn sql:insert-genre
  [genre]
  [(-> (insert-into :genre)
       (values [{:name genre}])
       (on-conflict :name)
       (do-nothing))])

(defn sql:insert-media-genres
  [media-id genres]
  (concat (map sql:insert-genre (seq genres))
          [(-> (insert-into :media_genres)
               (columns [:media_id :genre])
               (values (map (fn [genre] [media-id genre]) genres))
               (on-conflict :media_id :genre) (do-nothing))]))

(defn sql:insert-channels
  [channels]
  [(-> (insert-into :channel)
       (columns :name :full_name :id :description)
       (values (map (fn [[channel {:keys [::media/channel-id
                                         ::media/channel-fullname
                                         ::media/channel-description]}]]
                      [(name channel) channel-fullname channel-id channel-description])
                    channels))
       (on-conflict :name) (do-update-set :name :full_name :id :description))])

(s/fdef sql:insert-channels
  :args (s/cat :channels ::media/channel-descriptions))

(instrument 'sql:insert-channels)

(defn sql:insert-media-channels
  [media-id channels]
  [(-> (insert-into :media_channels)
       (columns [:media_id :channel])
       (values (map (fn [channel] [media-id channel]) channels))
       (on-conflict :media_id :channel) (do-nothing))])

(defn sql:insert-taglines
  [media-id taglines]
  [(-> (insert-into :taglines)
       (columns [:media_id :tagline])
       (values (map (fn [tagline] [media-id tagline])
                    taglines))
       (on-conflict :media_id :tagline (do-nothing)))])

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
    (concat [(-> (insert-into :media)
                 (values [row]))]
            (sql:insert-media-tags id tags)
            (sql:insert-media-genres id genres)
            (sql:insert-media-channels id channels)
            (sql:insert-taglines id taglines))))

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
              [[:array_agg [:distinct :taglines.tagline] :taglines]])
      (from :media)
      (left-join :media_tags [:= :media.id :media_tags.media_id])
      (left-join :media_channels [:= :media.id :media_channels.media_id])
      (left-join :media_genres [:= :media.id :media_genres.media_id])
      (left-join :media_genres [:= :media.id :taglines.media_id])
      (group-by :media.id)))

(defrecord SqlCatalog [store verbose]
  catalog/Catalog
  (add-media [_ media]
    (apply sql:exec! store verbose (sql:add-media media)))
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
    (apply sql:exec! store verbose (sql:insert-media-tags media-id tags)))
  (update-channels [_ channels]
    (sql:exec! store verbose (sql:insert-channels channels)))
  (add-media-channels [_ media-id channels]
    (apply sql:exec! store verbose (sql:insert-media-channels media-id channels)))
  (add-media-genres [_ media-id genres]
    (apply sql:exec! store verbose (sql:insert-media-genres media-id genres)))
  (get-media-by-channel [_ channel]
    (sql:fetch! store verbose
                (-> (sql:get-media)
                    (where [:= :media_channels.channel channel]))))
  (get-media-by-tag [_ tag]
    (sql:fetch! store verbose
                (-> (sql:get-media)
                    (where [:= :media_tags.tag tag]))))
  (get-media-by-genre [_ genre]
    (sql:fetch! store verbose
                (-> (sql:get-media)
                    (where [:= :media_genres.genre genre]))))
  (close-catalog! [_] true))

(defmethod catalog/initialize-catalog! :postgresql
  [{:keys [host port user password database verbose]
    :or   {verbose false}}]
  (let [db (jdbc/get-datasource {:dbtype   "postgresql"
                                 :dbname   database
                                 :user     user
                                 :password password
                                 :host     host
                                 :port     port})]
    (->SqlCatalog db verbose)))
