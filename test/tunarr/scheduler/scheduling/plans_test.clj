(ns tunarr.scheduler.scheduling.plans-test
  "Tests for the plans read-side: calendar helpers, operator-guidance storage
   (upsert/partial-update), and the preview/dashboard assembly over an
   in-memory H2-backed executor."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [next.jdbc :as jdbc]
            [tunarr.scheduler.sql.executor :as executor]
            [tunarr.scheduler.scheduling.storage :as storage]
            [tunarr.scheduler.scheduling.plans :as plans]))

(def ^:dynamic *ex* nil)

(defn- setup-schema [db]
  (jdbc/execute! db ["CREATE TABLE IF NOT EXISTS grids (
      id VARCHAR(64) PRIMARY KEY, grid_id VARCHAR(128), channel VARCHAR(128) NOT NULL,
      quarter VARCHAR(8) NOT NULL, cal_year INTEGER NOT NULL, version INTEGER NOT NULL DEFAULT 1,
      status VARCHAR(32) NOT NULL DEFAULT 'frozen', grid TEXT NOT NULL, feasibility TEXT,
      created_at TEXT NOT NULL, UNIQUE (channel, quarter, cal_year, version))"])
  (jdbc/execute! db ["CREATE TABLE IF NOT EXISTS overrides (
      id VARCHAR(64) PRIMARY KEY, overrides_id VARCHAR(128), channel VARCHAR(128) NOT NULL,
      cal_month VARCHAR(7) NOT NULL, version INTEGER NOT NULL DEFAULT 1,
      status VARCHAR(32) NOT NULL DEFAULT 'active', overrides TEXT NOT NULL DEFAULT '[]',
      created_at TEXT NOT NULL, UNIQUE (channel, cal_month, version))"])
  (jdbc/execute! db ["CREATE TABLE IF NOT EXISTS channel_guidance (
      channel VARCHAR(128) PRIMARY KEY, strategic_guidance TEXT, quarterly_theme TEXT,
      monthly_theme TEXT, planned_events TEXT NOT NULL DEFAULT '[]', updated_at TEXT NOT NULL)"]))

(use-fixtures :each
  (fn [t]
    (let [db (jdbc/get-datasource {:dbtype "h2:mem" :dbname (str "plans-" (random-uuid))
                                   :DB_CLOSE_DELAY "-1" :DATABASE_TO_LOWER "TRUE"})]
      (setup-schema db)
      (let [ex (executor/create-executor db :worker-count 1)]
        (binding [*ex* ex]
          (try (t) (finally (executor/close! ex))))))))

;; ---------------------------------------------------------------------------
;; Calendar helpers (pure)
;; ---------------------------------------------------------------------------

(deftest calendar-helpers
  (testing "quarter-of"
    (is (= "Q1" (plans/quarter-of "2026-01-05")))
    (is (= "Q1" (plans/quarter-of "2026-03-31")))
    (is (= "Q2" (plans/quarter-of "2026-04-01")))
    (is (= "Q4" (plans/quarter-of "2026-12-15"))))
  (testing "month-of / year-of"
    (is (= "2026-01" (plans/month-of "2026-01-05")))
    (is (= 2026 (plans/year-of "2026-07-09"))))
  (testing "months-in-range spans month boundaries, end exclusive"
    (is (= ["2026-01"] (plans/months-in-range "2026-01-05" "2026-01-12")))
    (is (= ["2026-01" "2026-02"] (plans/months-in-range "2026-01-28" "2026-02-03")))))

;; ---------------------------------------------------------------------------
;; Operator guidance storage
;; ---------------------------------------------------------------------------

(deftest guidance-defaults-to-nil
  (is (nil? (storage/get-guidance *ex* "Classic Comedy"))))

(deftest guidance-upsert-and-partial-update
  (testing "first write inserts"
    (let [g (storage/set-guidance! *ex* "Classic Comedy"
                                   {:strategic_guidance "lean into nostalgia"
                                    :planned_events ["Cheers marathon Jan 10"]})]
      (is (= "lean into nostalgia" (:strategic_guidance g)))
      (is (= ["Cheers marathon Jan 10"] (:planned_events g)))))
  (testing "a partial update leaves untouched fields intact"
    (let [g (storage/set-guidance! *ex* "Classic Comedy" {:quarterly_theme "New year, classic laughs"})]
      (is (= "New year, classic laughs" (:quarterly_theme g)))
      (is (= "lean into nostalgia" (:strategic_guidance g)))      ; preserved
      (is (= ["Cheers marathon Jan 10"] (:planned_events g)))))   ; preserved
  (testing "read-back matches"
    (let [g (storage/get-guidance *ex* "Classic Comedy")]
      (is (= "New year, classic laughs" (:quarterly_theme g)))
      (is (= "lean into nostalgia" (:strategic_guidance g))))))

(deftest planned-channels-unions-sources
  (storage/set-guidance! *ex* "Guidance Only" {:monthly_theme "x"})
  (storage/freeze-grid! *ex* "Grid Only" "Q1" 2026
                        {:channel "Grid Only" :strips []
                         :default_content {:media_id "random:x" :strategy "random"}})
  (is (= ["Grid Only" "Guidance Only"] (storage/planned-channels *ex*))))

;; ---------------------------------------------------------------------------
;; Preview + dashboard
;; ---------------------------------------------------------------------------

(def grid
  {:channel "Classic Comedy"
   :strips [{:strip_id "prime" :days "weekdays" :start "17:00" :end "18:00"
             :content {:media_id "series:seinfeld" :strategy "sequential"}}]
   :default_content {:media_id "random:sitcom" :strategy "random"}})

(def sat-override
  {:override_id "cheers-sat" :scope {:date "2026-01-10"} :start "10:00" :end "22:00"
   :content {:media_id "series:cheers" :strategy "sequential"} :mode "replace" :priority 0})

(deftest preview-expands-grid-and-overrides
  (storage/freeze-grid! *ex* "Classic Comedy" "Q1" 2026 grid :grid-id "g-1")
  (storage/store-overrides! *ex* "Classic Comedy" "2026-01" [sat-override])
  (let [out (plans/preview *ex* "Classic Comedy" "2026-01-05" "2026-01-12")]
    (is (= "g-1" (:grid_id out)))
    (is (seq (:slots out)))
    (testing "weekday Seinfeld strip shows up"
      (is (some #(= "series:seinfeld" (:media_id %)) (:slots out))))
    (testing "the Saturday override is applied from stored overrides"
      (is (some #(and (= "series:cheers" (:media_id %))
                      (= "2026-01-10" (subs (:start_time %) 0 10)))
                (:slots out))))))

(deftest preview-without-grid-is-empty
  (let [out (plans/preview *ex* "No Such Channel" "2026-01-05" "2026-01-12")]
    (is (nil? (:grid_id out)))
    (is (= [] (:slots out)))))

(deftest dashboard-combines-grid-overrides-guidance
  (storage/freeze-grid! *ex* "Classic Comedy" "Q1" 2026 grid :grid-id "g-1")
  (storage/store-overrides! *ex* "Classic Comedy" "2026-01" [sat-override])
  (storage/set-guidance! *ex* "Classic Comedy" {:monthly_theme "cozy january"})
  (let [d (plans/dashboard *ex* "Classic Comedy" "2026-01-15")]
    (is (= "Q1" (:quarter d)))
    (is (= "2026-01" (:month d)))
    (is (= "g-1" (-> d :grid :grid_id)))
    (is (= [sat-override] (-> d :overrides :overrides)))
    (is (= "cozy january" (-> d :guidance :monthly_theme)))))
