(ns tunarr.scheduler.scheduling.tasks-test
  "Tests for the scheduling task entry points (the public functions in
   `tunarr.scheduler.scheduling.tasks`). The read-side tests for the
   channel-UUID storage key already live in
   `tunarr.scheduler.http.api.plans-test`; this file is the **write-side**
   pin, focused on `channel-spec` — the helper that builds the
   Tunabrain-facing spec handed to `orch/run-quarterly!` and
   `orch/run-monthly!`.

   The bug pinned here: prior to this fix, `channel-spec` read
   `::media/channel-uuid` from the config only, with no DB fallback. When
   the configmap didn't carry that key (the production state for every
   channel in the current rollout), `:uuid` was `nil` and the
   subsequent `storage/freeze-grid!` write hit the `grids.channel`
   NOT-NULL constraint — every channel's quarterly grid failed to
   freeze. The fix mirrors the read-side `channel-storage-uuid`
   resolver: prefer the config value, fall back to a `channel` table
   lookup by `full_name` (case-insensitive) or `name` (the config-key
   slug)."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [next.jdbc :as jdbc]
            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.sql.executor :as executor]
            [tunarr.scheduler.scheduling.storage :as storage]
            [tunarr.scheduler.scheduling.orchestration :as orch]
            [tunarr.scheduler.backends.pseudovision.client :as pv]
            [tunarr.scheduler.scheduling.tasks :as tasks])
  (:import [java.time LocalDate]))

(def ^:dynamic *ex* nil)

(def ^:private spectrum-uuid "4d39eb17-b579-4b74-8eb1-74a65c3c65da")
(def ^:private hua-uuid      "bae3948c-f419-4331-995d-8aed11f2eadc")

(defn- setup-schema [db]
  (jdbc/execute! db ["
    CREATE TABLE IF NOT EXISTS channel (
      id VARCHAR(64) PRIMARY KEY,
      name VARCHAR(128) NOT NULL UNIQUE,
      full_name VARCHAR(128) NOT NULL,
      description TEXT,
      created_at TEXT,
      updated_at TEXT
    )"])
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
    )"]))

(defn- seed-channel [ex id slug full-name]
  (deref (executor/exec! ex
                         {:insert-into :channel
                          :values [{:id id :name slug :full_name full-name
                                    :description "" :created_at "" :updated_at ""}]})))

(defn- cfg-with-channel-uuid [uuid full-name description]
  {::media/channel-uuid    uuid
   ::media/channel-fullname full-name
   ::media/channel-description description
   ::media/channel-id       "pv-id-ignore"})

(defn- cfg-without-channel-uuid [full-name description]
  {::media/channel-fullname   full-name
   ::media/channel-description description
   ::media/channel-id         "pv-id-ignore"})

(use-fixtures :each
  (fn [t]
    (let [db (jdbc/get-datasource {:dbtype "h2:mem"
                                   :dbname (str "tasks-test-" (random-uuid))
                                   :DB_CLOSE_DELAY "-1"
                                   :DATABASE_TO_LOWER "TRUE"})]
      (setup-schema db)
      (let [ex (executor/create-executor db :worker-count 1)]
        (seed-channel ex spectrum-uuid "spectrum" "Sitcom Spectrum")
        (seed-channel ex hua-uuid      "hua"      "Hua Network")
        (binding [*ex* ex]
          (try (t) (finally (executor/close! ex))))))))

;; ---------------------------------------------------------------------------
;; channel-spec — the regression surface
;; ---------------------------------------------------------------------------

(deftest channel-spec-uses-config-uuid-when-present
  ;; When the configmap carries ::media/channel-uuid, channel-spec uses it
  ;; directly — no DB lookup, no surprises. This is the original (pre-bug)
  ;; happy path.
  (let [spec (#'tasks/channel-spec *ex*
              (cfg-with-channel-uuid spectrum-uuid "Sitcom Spectrum" "desc"))]
    (is (= "Sitcom Spectrum" (:name spec)))
    (is (= "desc"            (:description spec)))
    (is (= spectrum-uuid     (:uuid spec)))))

(deftest channel-spec-falls-back-to-db-when-config-uuid-missing
  ;; THIS is the regression pin. When the configmap does NOT carry
  ;; ::media/channel-uuid (the production state for every channel — the
  ;; configmap only has :name and :id), channel-spec must look the UUID
  ;; up in the `channel` table by full_name (case-insensitive). Before
  ;; the fix, :uuid was nil here, and storage/freeze-grid! blew up with
  ;; a NOT-NULL constraint violation.
  (let [spec (#'tasks/channel-spec *ex*
              (cfg-without-channel-uuid "Sitcom Spectrum" "desc"))]
    (is (= "Sitcom Spectrum" (:name spec)))
    (is (= "desc"            (:description spec)))
    (is (= spectrum-uuid     (:uuid spec))
        "channel-spec must resolve the UUID from the channel table when the configmap doesn't carry it")))

(deftest channel-spec-db-fallback-is-case-insensitive
  ;; Storage's find-channel-id does a case-insensitive match on
  ;; full_name, but the resolver passes whatever the configmap carries
  ;; — display names that the operator typed in mixed case. The DB
  ;; lookup must match regardless.
  (let [spec (#'tasks/channel-spec *ex*
              (cfg-without-channel-uuid "sitcom spectrum" "desc"))]
    (is (= spectrum-uuid (:uuid spec)))))

(deftest channel-spec-db-fallback-resolves-chinese-channel-by-slug
  ;; Hua Network's display name contains a non-ASCII character. The
  ;; fallback must still work when the configmap carries the display
  ;; name (the common case in production).
  (let [spec (#'tasks/channel-spec *ex*
              (cfg-without-channel-uuid "Hua Network" "Chinese content"))]
    (is (= hua-uuid (:uuid spec)))))

(deftest channel-spec-with-no-uuid-source-returns-nil
  ;; When neither the config nor the DB has the channel, :uuid is nil —
  ;; the caller surfaces the error (this is the documented contract
  ;; from before the fix). This pins the no-config-and-no-DB-row case
  ;; so a future change doesn't accidentally return a wrong UUID.
  (let [spec (#'tasks/channel-spec *ex*
              (cfg-without-channel-uuid "Nonexistent Channel" "x"))]
    (is (nil? (:uuid spec)))))

(deftest channel-spec-with-nil-executor-and-no-cfg-uuid-still-nil
  ;; The function accepts a nil executor to disable the DB fallback
  ;; (callers without a DB connection still get the original
  ;; config-only behavior). Pin it.
  (let [spec (#'tasks/channel-spec nil
              (cfg-without-channel-uuid "Sitcom Spectrum" "desc"))]
    (is (= "Sitcom Spectrum" (:name spec)))
    (is (nil? (:uuid spec)))))

;; ---------------------------------------------------------------------------
;; freeze-grid! — end-to-end check that the fix unblocks the storage write
;; ---------------------------------------------------------------------------

(deftest freeze-grid-with-channel-spec-uuid-lands-with-uuid-column
  ;; End-to-end: build a spec the way the task entry points do, then
  ;; write the grid. The SQL channel column must hold the resolved
  ;; UUID (not nil, not the display name). Before the fix, this
  ;; threw an SQL exception.
  (let [spec  (#'tasks/channel-spec *ex*
               (cfg-without-channel-uuid "Sitcom Spectrum" "desc"))
        row   (storage/freeze-grid! *ex* (:uuid spec) "Q3" 2026
                                     {:channel "Sitcom Spectrum" :strips []}
                                     :grid-id "tb-grid-x")]
    (is (= spectrum-uuid (:channel row))
        "grids.channel column must hold the TS channel.id UUID")
    (is (some? (:id row)))))

;; ---------------------------------------------------------------------------
;; run-quarterly! :date — target quarter selection + freeze-only guard
;; ---------------------------------------------------------------------------

(deftest quarterly-date-selects-quarter-and-gates-native-sync
  (testing "run-quarterly! derives the target quarter from ?date and only applies
            it to the live playout (passes :pv-channel-id → native sync) when that
            quarter is the CURRENT one; a future quarter is frozen only"
    (let [captured (atom [])]
      (with-redefs [pv/list-channels    (fn [_] [{:uuid "chan-uuid" :id 42}])
                    ;; Stub the per-channel orchestration so we observe exactly
                    ;; the quarter/year and whether a pv-channel-id (→ sync) was
                    ;; passed, without any real Tunabrain/PV traffic.
                    orch/run-quarterly! (fn [_comps _spec quarter year & {:keys [pv-channel-id]}]
                                          (swap! captured conj {:quarter quarter :year year
                                                                :pv-channel-id pv-channel-id})
                                          {:ok true})]
        (let [cfg  {::media/channel-uuid        "grid-uuid"
                    ::media/channel-fullname    "Sitcom Spectrum"
                    ::media/channel-description  ""
                    ::media/channel-id          "chan-uuid"}
              ctx  {:pseudovision {:base-url "http://pv"}
                    :channels     {:spectrum cfg}
                    :catalog      {:executor *ex*}}
              today       (LocalDate/now)
              ;; +3 months always lands in the immediately following quarter.
              next-q-date (.plusMonths today 3)]
          (testing "current quarter → native sync applied"
            (reset! captured [])
            (tasks/run-quarterly! ctx :date (str today))
            (is (= 42 (:pv-channel-id (first @captured)))
                "current-quarter run attaches + rebuilds the live playout"))
          (testing "future quarter → frozen only, live playout untouched"
            (reset! captured [])
            (tasks/run-quarterly! ctx :date (str next-q-date))
            (is (nil? (:pv-channel-id (first @captured)))
                "future-quarter run skips the native sync"))
          (testing "no ?date defaults to today's quarter and applies"
            (reset! captured [])
            (tasks/run-quarterly! ctx)
            (is (= 42 (:pv-channel-id (first @captured))))))))))
