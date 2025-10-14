(ns app.migrate
  (:require [migratus.core :as migratus])
  (:gen-class))

(defn- getenv [k] (System/getenv k))

(def ^:private cfg
  (delay
    {:store :database
     :migration-dir "migrations"
     :db {:dbtype   "postgresql"
          :host     (or (getenv "PGHOST") "postgres")
          :port     (or (getenv "PGPORT") "5432")
          :dbname   (or (getenv "PGDATABASE") "tunarr-scheduler")
          :user     (getenv "DB_USER")
          :password (getenv "DB_PASS")}}))

(defn migrate! []
  (migratus/migrate @cfg))

(defn run
  ([] (run nil))
  ([_]
   (try
     (migrate!)
     (println "✅ migrations complete")
     (System/exit 0)
     (catch Throwable t
       (binding [*out* *err*]
         (println "❌ migration failed:" (.getMessage t)))
       (System/exit 1)))))

(defn -main [& _]
  (run))
