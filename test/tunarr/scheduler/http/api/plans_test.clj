(ns tunarr.scheduler.http.api.plans-test
  "Regression tests for the plans HTTP handlers.

   The handlers in `tunarr.scheduler.http.api.plans` accept a `:channel`
   path param that, in production, is the config **slug** (e.g. 'goldenreels')
   — the same identifier used by POST `/api/scheduling/{daily,weekly,...}`.
   But the data is **stored** by the display **fullname** (e.g. 'Golden Reels'),
   so the handlers must translate. Before the fix, the read path was passing
   the slug straight to the storage layer and getting 404s on data that did
   exist. These tests pin the translation behavior."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [next.jdbc :as jdbc]
            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.sql.executor :as executor]
            [tunarr.scheduler.scheduling.storage :as storage]
            [tunarr.scheduler.http.api.plans :as plans-api]))

(def ^:dynamic *ctx* nil)

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
    )"])
  (jdbc/execute! db ["
    CREATE TABLE IF NOT EXISTS channel_guidance (
      channel VARCHAR(128) PRIMARY KEY,
      strategic_guidance TEXT,
      quarterly_theme TEXT,
      monthly_theme TEXT,
      planned_events TEXT NOT NULL DEFAULT '[]',
      updated_at TEXT NOT NULL
    )"]))

(defn- grid [media-id]
  {:channel "Golden Reels"
   :strips [{:strip_id "prime" :days "weekdays" :start "17:00" :end "18:00"
             :content {:media_id media-id :strategy "sequential"}}]
   :default_content {:media_id "random:classic" :strategy "random"}})

(defn- override [media-id]
  {:override_id "independence-day"
   :scope {:date "2026-07-04"}
   :start "19:00" :end "23:00"
   :content {:media_id media-id :strategy "specific"}
   :mode "replace" :priority 0})

(defn- mock-ctx [ex]
  {:catalog  {:executor ex}
   ;; Mirrors the production channels config: the config KEY is the slug
   ;; (used in URLs and POST endpoint selectors), the value carries the
   ;; fullname/description/id (what Tunabrain sees and what storage keys on).
   :channels {:goldenreels {::media/channel-fullname    "Golden Reels"
                            ::media/channel-description "Classic shows and movies"
                            ::media/channel-id          "e2d423d2-..."}
              :spectrum    {::media/channel-fullname    "Sitcom Spectrum"
                            ::media/channel-description "Sitcoms"
                            ::media/channel-id          "4d39eb17-..."}}})

(defn- req [channel & [extra]]
  {:parameters (merge {:path {:channel channel}
                       :query {}}
                     extra)})

(use-fixtures :each
  (fn [t]
    (let [db (jdbc/get-datasource {:dbtype "h2:mem" :dbname (str "plans-api-" (random-uuid))
                                   :DB_CLOSE_DELAY "-1" :DATABASE_TO_LOWER "TRUE"})]
      (setup-schema db)
      (let [ex (executor/create-executor db :worker-count 1)]
        (binding [*ctx* (mock-ctx ex)]
          (try (t) (finally (executor/close! ex))))))))

;; ---------------------------------------------------------------------------
;; get-grid-handler
;; ---------------------------------------------------------------------------

(deftest get-grid-handler-resolves-slug-to-storage-key
  (storage/freeze-grid! (:executor (:catalog *ctx*)) "Golden Reels" "Q1" 2026
                        (grid "series:cheers") :grid-id "tb-grid-1")
  (testing "URL with the config slug 'goldenreels' finds the grid stored by fullname"
    (let [handler (plans-api/get-grid-handler *ctx*)
          resp    (handler (req "goldenreels" {:query {:quarter "Q1" :year 2026}}))]
      (is (= 200 (:status resp)))
      (is (= "Golden Reels" (-> resp :body :channel)))
      (is (= "tb-grid-1" (-> resp :body :grid_id)))))
  (testing "URL with the display name 'Golden Reels' (legacy direct lookup) still works"
    (let [handler (plans-api/get-grid-handler *ctx*)
          resp    (handler (req "Golden Reels" {:query {:quarter "Q1" :year 2026}}))]
      (is (= 200 (:status resp)))
      (is (= "tb-grid-1" (-> resp :body :grid_id)))))
  (testing "URL with an unknown channel returns 404 (not crash)"
    (let [handler (plans-api/get-grid-handler *ctx*)
          resp    (handler (req "nonexistent" {:query {:quarter "Q1" :year 2026}}))]
      (is (= 404 (:status resp))))))

;; ---------------------------------------------------------------------------
;; list-grids-handler
;; ---------------------------------------------------------------------------

(deftest list-grids-handler-resolves-slug-to-storage-key
  (let [ex (:executor (:catalog *ctx*))]
    (storage/freeze-grid! ex "Golden Reels" "Q1" 2026 (grid "series:cheers") :grid-id "g-1")
    (storage/freeze-grid! ex "Golden Reels" "Q2" 2026 (grid "series:cosby") :grid-id "g-2"))
  (testing "list-grids via slug returns both versions newest-first"
    (let [handler (plans-api/list-grids-handler *ctx*)
          resp    (handler (req "goldenreels"))]
      (is (= 200 (:status resp)))
      (is (= 2 (count (-> resp :body :grids))))))
  (testing "list-grids via display name also works"
    (let [handler (plans-api/list-grids-handler *ctx*)
          resp    (handler (req "Golden Reels"))]
      (is (= 200 (:status resp)))
      (is (= 2 (count (-> resp :body :grids)))))))

;; ---------------------------------------------------------------------------
;; get-overrides-handler
;; ---------------------------------------------------------------------------

(deftest get-overrides-handler-resolves-slug-to-storage-key
  (let [ex (:executor (:catalog *ctx*))]
    (storage/store-overrides! ex "Golden Reels" "2026-07" [(override "movie:1776")]
                              :overrides-id "ovr-1"))
  (testing "URL slug finds overrides stored by fullname"
    (let [handler (plans-api/get-overrides-handler *ctx*)
          resp    (handler (req "goldenreels" {:query {:month "2026-07"}}))]
      (is (= 200 (:status resp)))
      (is (= "ovr-1" (-> resp :body :overrides_id)))))
  (testing "URL display name also works (legacy direct lookup)"
    (let [handler (plans-api/get-overrides-handler *ctx*)
          resp    (handler (req "Golden Reels" {:query {:month "2026-07"}}))]
      (is (= 200 (:status resp)))
      (is (= "ovr-1" (-> resp :body :overrides_id))))))

;; ---------------------------------------------------------------------------
;; list-overrides-handler
;; ---------------------------------------------------------------------------

(deftest list-overrides-handler-resolves-slug-to-storage-key
  (let [ex (:executor (:catalog *ctx*))]
    (storage/store-overrides! ex "Golden Reels" "2026-07" [(override "movie:1776")])
    (storage/store-overrides! ex "Golden Reels" "2026-08" []))
  (testing "list-overrides via slug returns the history"
    (let [handler (plans-api/list-overrides-handler *ctx*)
          resp    (handler (req "goldenreels"))]
      (is (= 200 (:status resp)))
      (is (= 2 (count (-> resp :body :overrides)))))))

;; ---------------------------------------------------------------------------
;; guidance handlers
;; ---------------------------------------------------------------------------

(deftest guidance-handlers-resolve-slug-to-storage-key
  (testing "PUT by slug stores under the display name; GET by slug finds it"
    ;; Simulate the cron task: store under the display name (per the storage
    ;; convention). The HTTP PUT path should translate the slug to that name.
    (let [ex (:executor (:catalog *ctx*))]
      (storage/set-guidance! ex "Golden Reels"
                             {:strategic_guidance "lean into nostalgia"}))
    (testing "GET guidance by slug"
      (let [handler (plans-api/get-guidance-handler *ctx*)
            resp    (handler (req "goldenreels"))]
        (is (= 200 (:status resp)))
        (is (= "lean into nostalgia" (-> resp :body :strategic_guidance)))))
    (testing "PUT guidance by slug translates to the display name"
      (let [handler (plans-api/put-guidance-handler *ctx*)
            resp    (handler (assoc (req "goldenreels")
                                    :parameters {:path {:channel "goldenreels"}
                                                 :body {:quarterly_theme "summer westerns"}}))]
        (is (= 200 (:status resp)))
        (is (= "summer westerns" (-> resp :body :quarterly_theme)))
        ;; The slug and the display name now both read the same row.
        (is (= "lean into nostalgia"
               (-> (plans-api/get-guidance-handler *ctx* (req "goldenreels"))
                   :body :strategic_guidance)))))))

;; ---------------------------------------------------------------------------
;; Cross-channel isolation
;; ---------------------------------------------------------------------------

(deftest slug-translation-is-per-channel
  (let [ex (:executor (:catalog *ctx*))]
    (storage/freeze-grid! ex "Golden Reels" "Q1" 2026 (grid "series:cheers") :grid-id "g-gold")
    (storage/freeze-grid! ex "Sitcom Spectrum" "Q1" 2026 (grid "series:friends") :grid-id "g-spec"))
  (testing "the goldenreels slug resolves to Golden Reels, not Sitcom Spectrum"
    (let [resp (plans-api/get-grid-handler *ctx*)
          r    (resp (req "goldenreels" {:query {:quarter "Q1" :year 2026}}))]
      (is (= "g-gold" (-> r :body :grid_id)))))
  (testing "the spectrum slug resolves to Sitcom Spectrum, not Golden Reels"
    (let [resp (plans-api/get-grid-handler *ctx*)
          r    (resp (req "spectrum" {:query {:quarter "Q1" :year 2026}}))]
      (is (= "g-spec" (-> r :body :grid_id))))))
