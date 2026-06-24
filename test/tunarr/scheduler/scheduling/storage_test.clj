(ns tunarr.scheduler.scheduling.storage-test
  "Tests for grid/override persistence over an in-memory H2-backed executor."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [next.jdbc :as jdbc]
            [tunarr.scheduler.sql.executor :as executor]
            [tunarr.scheduler.scheduling.storage :as store]))

(def ^:dynamic *ex* nil)

(defn- setup-schema [db]
  (jdbc/execute! db ["
    CREATE TABLE IF NOT EXISTS grids (
      id VARCHAR(64) PRIMARY KEY,
      grid_id VARCHAR(128),
      channel VARCHAR(128) NOT NULL,
      quarter VARCHAR(8) NOT NULL,
      cal_year INTEGER NOT NULL,
      version INTEGER NOT NULL DEFAULT 1,
      status VARCHAR(32) NOT NULL DEFAULT 'frozen',
      grid TEXT NOT NULL,
      feasibility TEXT,
      created_at TEXT NOT NULL,
      UNIQUE (channel, quarter, cal_year, version)
    )"])
  (jdbc/execute! db ["
    CREATE TABLE IF NOT EXISTS overrides (
      id VARCHAR(64) PRIMARY KEY,
      overrides_id VARCHAR(128),
      channel VARCHAR(128) NOT NULL,
      cal_month VARCHAR(7) NOT NULL,
      version INTEGER NOT NULL DEFAULT 1,
      status VARCHAR(32) NOT NULL DEFAULT 'active',
      overrides TEXT NOT NULL DEFAULT '[]',
      created_at TEXT NOT NULL,
      UNIQUE (channel, cal_month, version)
    )"]))

(use-fixtures :each
  (fn [t]
    ;; DATABASE_TO_LOWER makes H2 fold unquoted identifiers to lower case, so
    ;; next.jdbc reports :grids/id etc. the same way Postgres does in production.
    (let [db (jdbc/get-datasource {:dbtype "h2:mem" :dbname (str "store-" (random-uuid))
                                   :DB_CLOSE_DELAY "-1" :DATABASE_TO_LOWER "TRUE"})]
      (setup-schema db)
      ;; A single worker serializes jobs on one connection, so the version-read
      ;; and the supersede+insert in freeze-grid! see each other's writes
      ;; deterministically (multiple H2 connections to the same in-mem DB can lag
      ;; under parallel test load).
      (let [ex (executor/create-executor db :worker-count 1)]
        (binding [*ex* ex]
          (try (t) (finally (executor/close! ex))))))))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn- grid [media-id]
  {:channel "Classic Comedy"
   :strips [{:strip_id "prime" :days "weekdays" :start "17:00" :end "18:00"
             :content {:media_id media-id :strategy "sequential"}}]
   :default_content {:media_id "random:sitcom" :strategy "random"}})

(defn- override [media-id]
  {:override_id "cheers-sat"
   :scope {:date "2026-01-10"}
   :start "10:00" :end "22:00"
   :content {:media_id media-id :strategy "sequential"}
   :mode "replace" :priority 0})

;; ---------------------------------------------------------------------------
;; Grids
;; ---------------------------------------------------------------------------

(deftest grid-freeze-and-read-back
  (let [stored (store/freeze-grid! *ex* "Classic Comedy" "Q1" 2026 (grid "series:seinfeld")
                                   :grid-id "tb-grid-1")]
    (is (= 1 (:version stored)))
    (is (= "frozen" (:status stored)))
    (testing "current-grid returns the stored grid, round-tripped through JSON"
      (let [current (store/current-grid *ex* "Classic Comedy" "Q1" 2026)]
        (is (= "tb-grid-1" (:grid_id current)))
        (is (= (grid "series:seinfeld") (:grid current)))))
    (testing "get-grid by id"
      (is (= (:id stored) (:id (store/get-grid *ex* (:id stored))))))))

(deftest grid-versioning-supersedes-prior
  (store/freeze-grid! *ex* "Classic Comedy" "Q1" 2026 (grid "series:seinfeld"))
  (let [v2 (store/freeze-grid! *ex* "Classic Comedy" "Q1" 2026 (grid "series:cheers"))]
    (is (= 2 (:version v2)))
    (testing "current points at the newest version"
      (let [current (store/current-grid *ex* "Classic Comedy" "Q1" 2026)]
        (is (= 2 (:version current)))
        (is (= "series:cheers" (-> current :grid :strips first :content :media_id)))))
    (testing "exactly one frozen version remains; the prior is superseded"
      (let [all (store/list-grids *ex* :channel "Classic Comedy")]
        (is (= 2 (count all)))
        (is (= 1 (count (filter #(= "frozen" (:status %)) all))))
        (is (= 1 (count (filter #(= "superseded" (:status %)) all))))))))

(deftest grid-keys-are-independent
  (store/freeze-grid! *ex* "Classic Comedy" "Q1" 2026 (grid "series:seinfeld"))
  (store/freeze-grid! *ex* "Classic Comedy" "Q2" 2026 (grid "series:cheers"))
  (is (= 1 (:version (store/current-grid *ex* "Classic Comedy" "Q1" 2026))))
  (is (= 1 (:version (store/current-grid *ex* "Classic Comedy" "Q2" 2026))))
  (is (nil? (store/current-grid *ex* "Classic Comedy" "Q3" 2026))))

(deftest grid-validation-rejects-garbage
  (testing "a non-conforming Grid is refused before it hits the DB"
    (is (thrown? clojure.lang.ExceptionInfo
                 (store/freeze-grid! *ex* "Classic Comedy" "Q1" 2026 {:not "a grid"})))))

(deftest grid-stores-feasibility-snapshot
  (let [report {:horizon_start "2026-01-01" :horizon_end "2026-04-01"
                :overall_status "ok" :strip_findings [] :overlaps []
                :uncovered_intervals [] :notes []}
        stored (store/freeze-grid! *ex* "Classic Comedy" "Q1" 2026 (grid "series:seinfeld")
                                   :feasibility report)]
    (is (= report (:feasibility stored)))
    (is (= report (:feasibility (store/get-grid *ex* (:id stored)))))))

;; ---------------------------------------------------------------------------
;; Overrides
;; ---------------------------------------------------------------------------

(deftest overrides-store-and-read-back
  (let [stored (store/store-overrides! *ex* "Classic Comedy" "2026-01" [(override "series:cheers")]
                                       :overrides-id "tb-ovr-1")]
    (is (= 1 (:version stored)))
    (let [current (store/current-overrides *ex* "Classic Comedy" "2026-01")]
      (is (= "tb-ovr-1" (:overrides_id current)))
      (is (= [(override "series:cheers")] (:overrides current))))))

(deftest overrides-empty-list-is-valid
  (testing "an empty override set is normal (a month with no special plans)"
    (let [stored (store/store-overrides! *ex* "Classic Comedy" "2026-02" [])]
      (is (= [] (:overrides stored)))
      (is (= [] (:overrides (store/current-overrides *ex* "Classic Comedy" "2026-02")))))))

(deftest overrides-versioning-supersedes-prior
  (store/store-overrides! *ex* "Classic Comedy" "2026-01" [(override "series:cheers")])
  (let [v2 (store/store-overrides! *ex* "Classic Comedy" "2026-01" [(override "series:frasier")])]
    (is (= 2 (:version v2)))
    (is (= "series:frasier"
           (-> (store/current-overrides *ex* "Classic Comedy" "2026-01")
               :overrides first :content :media_id)))
    (is (= 1 (count (filter #(= "active" (:status %))
                            (store/list-overrides *ex* :channel "Classic Comedy")))))))

(deftest overrides-validation-rejects-garbage
  (is (thrown? clojure.lang.ExceptionInfo
               (store/store-overrides! *ex* "Classic Comedy" "2026-01" [{:bad "override"}]))))
