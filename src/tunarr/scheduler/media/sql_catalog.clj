(ns tunarr.scheduler.media.sql-catalog
  (:require [tunarr.scheduler.media.catalog :as catalog]
            [honey.sql :as sql]
            [honey.sql.helpers :refer [select from join where insert-into update values set delete-from returning]]
            [next.jdbc :as jdbc]
            [tunarr.scheduler.media :as media]
            [clojure.stacktrace :refer [print-stack-trace]]))

(defn capture-stack-trace
  [e]
  (with-out-str (print-stack-trace e)))

(defn exec!
  "Execute the given SQL statements in a transaction"
  [store & sqls]
  (letfn [(log! [sql]
            (when (:verbose store)
              (println (str "executing: " sql)))
            sql)]
    (try
      (jdbc/with-transaction [tx (jdbc/get-connection (:datasource store))]
        (doseq [sql sqls]
          (jdbc/execute! tx (log! (sql/format sql)))))
      (catch Exception e
        (when (:verbose store)
          (println (capture-stack-trace e)))
        (throw e)))))

(defn fetch!  
  "Fetch results for the given SQL query" 
  [store sql]
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

(defn sql:add-media [db media]
  (exec! db
         (-> (insert-into :media)
             (values (map
                      (fn [m] (select-keys m media/media-fields))
                      media)))))

(defn sql:get-media [db]
  (fetch! db
          (-> (apply select media/media-fields)
              (from :media)
              )))

(defrecord SqlCatalog [db]
  catalog/Catalog
  (add-media [_ media])
  (get-media [_])
  (get-media-by-library-id [_ library-id])
  (get-media-by-id [_ media-id])
  (set-media-tags [_ media-id tags])
  (set-media-channels [_ media-id channels])
  (get-media-by-channel [_ channel])
  (get-media-by-tag [_ tag]))
