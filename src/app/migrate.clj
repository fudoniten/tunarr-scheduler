(ns app.migrate
  (:require [migratus.core :as migratus]))

(defn- getenv [k] (System/getenv k))

(defn- build-jdbc-url []
  (let [url (getenv "JDBC_URL")]
    (if (seq url)
      url
      (let [host (or (getenv "PGHOST") "postgres")
            port (or (getenv "PGPORT") "5432")
            db   (or (getenv "PGDATABASE") "appdb")]
        (format "jdbc:postgresql://%s:%s/%s" host port db)))))

(def ^:private cfg
  (delay
    {:store :database
     :migration-dir "migrations"
     :db {:connection-uri (build-jdbc-url)
          :user (getenv "DB_USER")
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
