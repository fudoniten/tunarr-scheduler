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

;; ---------------------------------------------------------------------------
;; Regression: the layered-grid storage now uses the channel UUID as the
;; canonical key, not the display name. Earlier revisions stored the
;; human-readable `full_name` (e.g. "Enigma TV") in `grids.channel` etc.,
;; which collided with the slug used in HTTP URLs and the case-insensitive
;; fullname form used by older guidance rows. This test exercises the
;; UUID-as-key contract.
;; ---------------------------------------------------------------------------

(def uuid-grid
  {:channel "Enigma TV"                       ; display name (content)
   :strips [{:strip_id "prime" :days "weekdays" :start "17:00" :end "18:00"
             :content {:media_id "series:enigma" :strategy "sequential"}}]
   :default_content {:media_id "random:enigma" :strategy "random"}})

(deftest storage-keys-by-uuid-not-display-name
  (testing "freeze-grid! / current-grid round-trip on a UUID key"
    (storage/freeze-grid! *ex* "321b6f56-96bb-49bd-b826-72cbfcb786c6" "Q3" 2026 uuid-grid
                          :grid-id "g-uuid-1")
    (let [stored (storage/current-grid *ex* "321b6f56-96bb-49bd-b826-72cbfcb786c6" "Q3" 2026)]
      (is (= "g-uuid-1" (:grid_id stored)))
      (is (= "321b6f56-96bb-49bd-b826-72cbfcb786c6" (:channel stored)))
      (is (= "Enigma TV" (-> stored :grid :channel)))))
  (testing "store-overrides! / current-overrides round-trip on a UUID key"
    (storage/store-overrides! *ex* "321b6f56-96bb-49bd-b826-72cbfcb786c6" "2026-07" [sat-override])
    (let [stored (storage/current-overrides *ex* "321b6f56-96bb-49bd-b826-72cbfcb786c6" "2026-07")]
      (is (= "321b6f56-96bb-49bd-b826-72cbfcb786c6" (:channel stored)))))
  (testing "set-guidance! / get-guidance round-trip on a UUID key"
    (storage/set-guidance! *ex* "321b6f56-96bb-49bd-b826-72cbfcb786c6"
                            {:monthly_theme "mystery in july"})
    (let [g (storage/get-guidance *ex* "321b6f56-96bb-49bd-b826-72cbfcb786c6")]
      (is (= "321b6f56-96bb-49bd-b826-72cbfcb786c6" (:channel g)))
      (is (= "mystery in july" (:monthly_theme g)))))
  (testing "two distinct channels keyed by UUID stay distinct even if their
            display names share a token ('Enigma TV' vs 'enigma tv' — the
            case that broke the pre-fix read paths)"
    (let [uuid-a "11111111-1111-1111-1111-111111111111"
          uuid-b "22222222-2222-2222-2222-222222222222"]
      (storage/set-guidance! *ex* uuid-a {:strategic_guidance "A"})
      (storage/set-guidance! *ex* uuid-b {:strategic_guidance "B"})
      (is (= "A" (:strategic_guidance (storage/get-guidance *ex* uuid-a))))
      (is (= "B" (:strategic_guidance (storage/get-guidance *ex* uuid-b)))))))
