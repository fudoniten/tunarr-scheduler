(ns tunarr.scheduler.test-support.db
  "Shared embedded-Postgres harness for SQL-backed tests.

   Production runs on Postgres and the SQL layer emits Postgres-only upserts
   (`INSERT ... ON CONFLICT ... DO UPDATE`). H2 cannot parse those, so tests that
   exercise the real SqlCatalog run against a throwaway embedded Postgres instead
   of an in-memory H2 database.

   One Postgres instance is booted per JVM and the real migrations
   (resources/migrations) are applied once. Each test gets a clean slate via
   `reset!`, which truncates every application table (keeping the schema and the
   migratus bookkeeping table intact)."
  (:require [next.jdbc :as jdbc]
            [migratus.core :as migratus])
  (:import [io.zonky.test.db.postgres.embedded EmbeddedPostgres]))

(defonce ^:private embedded
  (delay
    (let [pg (.start (EmbeddedPostgres/builder))
          ds (.getPostgresDatabase pg)]
      (migratus/migrate {:store         :database
                         :migration-dir "migrations"
                         :db            {:datasource ds}})
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. (fn [] (try (.close pg) (catch Throwable _)))))
      {:pg pg :datasource ds})))

(defn datasource
  "The shared embedded-Postgres javax.sql.DataSource (migrations already applied)."
  []
  (:datasource @embedded))

(defn- application-tables
  "Every table in the public schema except migratus' own bookkeeping table."
  [conn]
  (->> (jdbc/execute! conn
                      ["SELECT tablename FROM pg_tables
                         WHERE schemaname = 'public'
                           AND tablename <> 'schema_migrations'"])
       (map :pg_tables/tablename)))

(defn reset!
  "Truncate every application table so each test starts from an empty schema.
   TRUNCATE ... CASCADE clears dependent rows in one shot regardless of FK order."
  []
  (with-open [conn (jdbc/get-connection (datasource))]
    (let [tables (application-tables conn)]
      (when (seq tables)
        (jdbc/execute! conn
                       [(str "TRUNCATE TABLE "
                             (clojure.string/join ", " (map #(str "\"" % "\"") tables))
                             " RESTART IDENTITY CASCADE")])))))

(defn with-clean-db
  "clojure.test fixture: reset the shared database before each test."
  [t]
  (reset!)
  (t))
