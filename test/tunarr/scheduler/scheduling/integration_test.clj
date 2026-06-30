(ns tunarr.scheduler.scheduling.integration-test
  "Tests for the Pseudovision boundary: kebab↔snake conversion, CatalogProfile
   assembly, and DailySlot publication (with the PV client stubbed)."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [next.jdbc :as jdbc]
            [tunarr.scheduler.sql.executor :as executor]
            [tunarr.scheduler.scheduling.storage :as storage]
            [tunarr.scheduler.scheduling.contracts :as c]
            [tunarr.scheduler.backends.pseudovision.client :as pv]
            [tunarr.scheduler.scheduling.integration :as integ]))

;; The exact aggregate body from the Pseudovision spec (kebab-case).
(def pv-catalog-aggregate
  {:channel-scope nil
   :total-items 100
   :total-episodes 80
   :movie-count 20
   :shows [{:media-id "series:42"
            :title "Cheers"
            :genres ["comedy"]
            :episode-count 275
            :available-episode-count 275
            :avg-runtime-minutes 22.5
            :tags ["channel:comedy"]}]
   :genres [{:genre "comedy" :show-count 1 :episode-count 275}]
   :runtime-histogram [{:label "20-30min" :min-minutes 20 :max-minutes 30 :item-count 10}
                       {:label "120min+" :min-minutes 120 :max-minutes nil :item-count 3}]
   :generated-at "2026-06-24T12:00:00Z"})

;; ---------------------------------------------------------------------------
;; Key-case conversion
;; ---------------------------------------------------------------------------

(deftest ^:eftest/synchronized snake-kebab-roundtrip
  (testing "kebab → snake converts nested keys"
    (let [snake (integ/->snake pv-catalog-aggregate)]
      (is (= 100 (:total_items snake)))
      (is (= "series:42" (-> snake :shows first :media_id)))
      (is (= 275 (-> snake :shows first :available_episode_count)))
      (is (= 22.5 (-> snake :shows first :avg_runtime_minutes)))
      (is (= 1 (-> snake :genres first :show_count)))
      (is (= 30 (-> snake :runtime_histogram first :max_minutes)))))
  (testing "round-trips back to kebab"
    (is (= pv-catalog-aggregate (integ/->kebab (integ/->snake pv-catalog-aggregate)))))
  (testing "string values are untouched (only keys change)"
    (is (= "channel:comedy" (-> (integ/->snake pv-catalog-aggregate) :shows first :tags first)))))

(deftest ^:eftest/synchronized converted-profile-conforms-to-contract
  (testing "a converted Pseudovision aggregate satisfies contracts/CatalogProfile"
    (is (nil? (c/humanize c/CatalogProfile (integ/->snake pv-catalog-aggregate)))))
  (testing "the open-ended top runtime bucket (nil max) is accepted"
    (let [snake (integ/->snake pv-catalog-aggregate)]
      (is (nil? (-> snake :runtime_histogram second :max_minutes))))))

;; ---------------------------------------------------------------------------
;; fetch-catalog-profile
;; ---------------------------------------------------------------------------

(deftest ^:eftest/synchronized fetch-catalog-profile-converts-and-passes-opts
  (let [capture (atom nil)]
    (with-redefs [pv/get-catalog-aggregate (fn [_cfg opts]
                                             (reset! capture opts)
                                             pv-catalog-aggregate)]
      (let [profile (integ/fetch-catalog-profile ::cfg {:channel "Classic Comedy"})]
        (is (= {:channel "Classic Comedy"} @capture))
        (is (= 100 (:total_items profile)))
        (is (nil? (c/humanize c/CatalogProfile profile)))))))

;; ---------------------------------------------------------------------------
;; publish-daily-slots! (snake → kebab on the wire)
;; ---------------------------------------------------------------------------

(def snake-slot
  {:start_time "2026-06-24T08:00:00" :end_time "2026-06-24T10:00:00"
   :media_id "series:42" :media_selection_strategy "sequential"
   :category_filters ["comedy" "channel:comedy"] :notes ["Season 1 block"]})

(deftest ^:eftest/synchronized publish-converts-slots-to-kebab
  (let [capture (atom nil)
        result  {:ingested 1 :skipped 0 :errors [] :channel-id 7}]
    (with-redefs [pv/push-daily-slots! (fn [_cfg channel-id slots]
                                         (reset! capture {:channel-id channel-id :slots slots})
                                         result)]
      (let [out (integ/publish-daily-slots! ::cfg 7 [snake-slot])]
        (is (= result out))
        (is (= 7 (:channel-id @capture)))
        (testing "the slot reached the client in kebab-case"
          (let [sent (first (:slots @capture))]
            (is (= "2026-06-24T08:00:00" (:start-time sent)))
            (is (= "series:42" (:media-id sent)))
            (is (= "sequential" (:media-selection-strategy sent)))
            (is (= ["comedy" "channel:comedy"] (:category-filters sent)))
            (is (not (contains? sent :start_time)))))))))

;; ---------------------------------------------------------------------------
;; publish-week! (expand stored plan → push)
;; ---------------------------------------------------------------------------

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
      created_at TEXT NOT NULL, UNIQUE (channel, cal_month, version))"]))

(use-fixtures :each
  (fn [t]
    (let [db (jdbc/get-datasource {:dbtype "h2:mem" :dbname (str "integ-" (random-uuid))
                                   :DB_CLOSE_DELAY "-1" :DATABASE_TO_LOWER "TRUE"})]
      (setup-schema db)
      (let [ex (executor/create-executor db :worker-count 1)]
        (binding [*ex* ex]
          (try (t) (finally (executor/close! ex))))))))

(def grid
  {:channel "Classic Comedy"
   :strips [{:strip_id "prime" :days "weekdays" :start "17:00" :end "18:00"
             :content {:media_id "series:42" :strategy "sequential"}}]
   :default_content {:media_id "random:comedy" :strategy "random"}})

(deftest ^:eftest/synchronized publish-week-expands-and-pushes
  (storage/freeze-grid! *ex* "Classic Comedy" "Q1" 2026 grid :grid-id "g-1")
  (let [capture (atom nil)]
    (with-redefs [pv/push-daily-slots! (fn [_cfg channel-id slots]
                                         (reset! capture {:channel-id channel-id :slots slots})
                                         {:ingested (count slots) :skipped 0 :errors [] :channel-id channel-id})]
      (let [out (integ/publish-week! *ex* ::cfg "Classic Comedy" 7 "2026-01-05" "2026-01-12")]
        (is (= 7 (:pv-channel-id out)))
        (is (pos? (:slot-count out)))
        (is (= (:slot-count out) (-> out :result :ingested)))
        (testing "pushed slots are kebab-case"
          (is (every? #(contains? % :start-time) (:slots @capture)))
          (is (some #(= "series:42" (:media-id %)) (:slots @capture))))))))

(deftest ^:eftest/synchronized publish-week-skips-without-grid
  (with-redefs [pv/push-daily-slots! (fn [& _] (throw (ex-info "should not push" {})))]
    (let [out (integ/publish-week! *ex* ::cfg "No Grid Channel" 9 "2026-01-05" "2026-01-12")]
      (is (= :no-grid (:skipped out))))))
