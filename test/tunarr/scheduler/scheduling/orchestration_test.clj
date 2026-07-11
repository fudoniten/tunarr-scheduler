(ns tunarr.scheduler.scheduling.orchestration-test
  "Tests for the propose → check → repair → freeze loop. The external calls are
   injected through the components map (no global redefs), so these run safely in
   parallel; storage + feasibility are real over an in-memory H2 executor."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [next.jdbc :as jdbc]
            [tunarr.scheduler.sql.executor :as executor]
            [tunarr.scheduler.scheduling.storage :as storage]
            [tunarr.scheduler.scheduling.orchestration :as orch]
            [tunarr.scheduler.backends.pseudovision.client :as pv]
            [tunarr.scheduler.tunabrain :as tb]))

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
    (let [db (jdbc/get-datasource {:dbtype "h2:mem" :dbname (str "orch-" (random-uuid))
                                   :DB_CLOSE_DELAY "-1" :DATABASE_TO_LOWER "TRUE"})]
      (setup-schema db)
      (let [ex (executor/create-executor db :worker-count 1)]
        (binding [*ex* ex]
          (try (t) (finally (executor/close! ex))))))))

(def channel-uuid "00000000-0000-0000-0000-000000000001")
(def channel-spec {:name "Classic Comedy" :description "vintage sitcoms"
                     :uuid channel-uuid})

;; series:42 has only 5 available episodes — far short of a quarter of weekdays.
(def profile
  {:channel_scope "Classic Comedy" :total_items 100 :total_episodes 80 :movie_count 20
   :shows [{:media_id "series:42" :title "Cheers" :episode_count 275 :available_episode_count 5}]
   :genres [{:genre "comedy" :show_count 1 :episode_count 275}]
   :runtime_histogram []})

;; A grid that strips series:42 every weekday ⇒ feasibility shortfall (blocked).
(def shortfall-grid
  {:channel "Classic Comedy"
   :strips [{:strip_id "prime" :days "weekdays" :start "17:00" :end "18:00"
             :content {:media_id "series:42" :strategy "sequential"}}]
   :default_content {:media_id "random:comedy" :strategy "random"}})

;; A grid with no demanding strips ⇒ feasibility ok.
(def feasible-grid
  {:channel "Classic Comedy"
   :strips []
   :default_content {:media_id "random:comedy" :strategy "random"}})

(defn- base-components
  "Components with the external calls stubbed via injection. Overrides merge in."
  [overrides]
  (merge {:executor *ex* :tunabrain ::tb :pv-config ::pv
          :fetch-profile (fn [_ _] profile)}
         overrides))

;; ---------------------------------------------------------------------------
;; Quarterly: propose → check → repair → freeze
;; ---------------------------------------------------------------------------

(deftest quarterly-feasible-proposal-freezes-without-repair
  (let [repairs (atom 0)
        comps (base-components
               {:propose-grid (fn [_ _] {:grid_id "g-1" :grid feasible-grid})
                :repair-grid  (fn [_ _] (swap! repairs inc) {:grid feasible-grid})})
        out (orch/run-quarterly! comps channel-spec "Q1" 2026)]
    (is (= "ok" (:feasibility-status out)))
    (is (= 0 (:repairs out)))
    (is (= 0 @repairs) "repair must not be called for a feasible proposal")
    (testing "it is frozen and retrievable, with the feasibility snapshot"
      (let [stored (storage/current-grid *ex* channel-uuid "Q1" 2026)]
        (is (= "g-1" (:grid_id stored)))
        (is (= "ok" (-> stored :feasibility :overall_status)))))))

(deftest quarterly-repairs-then-freezes
  (let [calls (atom 0)
        comps (base-components
               {:propose-grid (fn [_ _] {:grid_id "g-1" :grid shortfall-grid})
                :repair-grid  (fn [_ _] (swap! calls inc) {:grid feasible-grid})})
        out (orch/run-quarterly! comps channel-spec "Q1" 2026)]
    (is (= 1 @calls) "exactly one repair round")
    (is (= 1 (:repairs out)))
    (is (= "ok" (:feasibility-status out)))))

(deftest quarterly-repair-loop-is-bounded
  (let [calls (atom 0)
        comps (base-components
               {:propose-grid (fn [_ _] {:grid_id "g-1" :grid shortfall-grid})
                :repair-grid  (fn [_ _] (swap! calls inc) {:grid shortfall-grid})})
        out (orch/run-quarterly! comps channel-spec "Q1" 2026 :max-repairs 2)]
    (is (= 2 @calls))
    (is (= 2 (:repairs out)))
    (is (= "blocked" (:feasibility-status out)) "freezes best-effort, flagged blocked")
    (is (some? (storage/current-grid *ex* channel-uuid "Q1" 2026)))))

(deftest quarterly-feeds-operator-guidance-into-proposal
  (storage/set-guidance! *ex* channel-uuid
                         {:quarterly_theme "New year, classic laughs"
                          :strategic_guidance "lean nostalgic"})
  (let [captured (atom nil)
        comps (base-components
               {:propose-grid (fn [_ req] (reset! captured req)
                                {:grid_id "g-1" :grid feasible-grid})})]
    (orch/run-quarterly! comps channel-spec "Q1" 2026)
    (is (= "New year, classic laughs" (:quarterly-theme @captured)))
    (is (= "lean nostalgic" (:strategic-guidance @captured)))
    (is (= channel-spec (:channel @captured)))))

;; ---------------------------------------------------------------------------
;; Monthly: propose overrides against the frozen grid
;; ---------------------------------------------------------------------------

(deftest monthly-requires-a-frozen-grid
  (is (thrown? clojure.lang.ExceptionInfo
               (orch/run-monthly! (base-components {}) channel-spec "2026-01"))))

(deftest monthly-stores-overrides-against-grid
  (storage/freeze-grid! *ex* channel-uuid "Q1" 2026 feasible-grid :grid-id "g-1")
  (storage/set-guidance! *ex* channel-uuid {:planned_events ["Cheers marathon Jan 10"]})
  (let [captured (atom nil)
        override {:override_id "o-1" :scope {:date "2026-01-10"} :start "10:00" :end "22:00"
                  :content {:media_id "series:42" :strategy "sequential"} :mode "replace" :priority 0}
        comps (base-components
               {:propose-overrides (fn [_ req] (reset! captured req)
                                     {:overrides_id "ov-1" :overrides [override]})})
        stored (orch/run-monthly! comps channel-spec "2026-01")]
    (testing "guidance + frozen grid were passed to the proposal"
      (is (= ["Cheers marathon Jan 10"] (:planned-events @captured)))
      (is (= feasible-grid (:grid @captured))))
    (testing "overrides are stored and retrievable"
      (is (= "ov-1" (:overrides_id stored)))
      (is (= [override] (:overrides (storage/current-overrides *ex* channel-uuid "2026-01")))))))

(deftest monthly-empty-overrides-is-normal
  (storage/freeze-grid! *ex* channel-uuid "Q1" 2026 feasible-grid :grid-id "g-1")
  (let [comps (base-components
               {:propose-overrides (fn [_ _] {:overrides_id "ov-empty" :overrides []})})
        stored (orch/run-monthly! comps channel-spec "2026-01")]
    (is (= [] (:overrides stored)))))

;; ---------------------------------------------------------------------------
;; run-quarterly! :pv-channel-id -> sync-native-schedule! wiring
;; ---------------------------------------------------------------------------

(deftest quarterly-syncs-native-schedule-when-pv-channel-id-given
  (let [sync-calls (atom [])
        comps (base-components
               {:propose-grid (fn [_ _] {:grid_id "g-1" :grid feasible-grid})
                :sync-schedule (fn [pv-config pv-channel-id grid channel-tag]
                                (swap! sync-calls conj [pv-config pv-channel-id grid channel-tag])
                                {:schedule-id 9 :slot-count 3 :warnings []})})
        out (orch/run-quarterly! comps channel-spec "Q1" 2026
                                 :pv-channel-id 42 :catalog-tag "channel:classic-comedy")]
    (is (= 1 (count @sync-calls)))
    (is (= [::pv 42 "channel:classic-comedy"]
           (let [[pv-config pv-channel-id _grid channel-tag] (first @sync-calls)]
             [pv-config pv-channel-id channel-tag])))
    (is (= {:ok {:schedule-id 9 :slot-count 3 :warnings []}} (:native-sync out)))))

(deftest quarterly-skips-native-sync-without-pv-channel-id
  (let [sync-calls (atom 0)
        comps (base-components
               {:propose-grid (fn [_ _] {:grid_id "g-1" :grid feasible-grid})
                :sync-schedule (fn [& _] (swap! sync-calls inc) {})})
        out (orch/run-quarterly! comps channel-spec "Q1" 2026)]
    (is (= 0 @sync-calls))
    (is (not (contains? out :native-sync)))))

(deftest quarterly-native-sync-failure-does-not-fail-the-freeze
  (let [comps (base-components
               {:propose-grid (fn [_ _] {:grid_id "g-1" :grid feasible-grid})
                :sync-schedule (fn [& _] (throw (ex-info "pv unreachable" {})))})
        out (orch/run-quarterly! comps channel-spec "Q1" 2026 :pv-channel-id 42)]
    (is (= "ok" (:feasibility-status out)) "the grid still froze")
    (is (= "pv unreachable" (:error (:native-sync out))))
    (is (some? (storage/current-grid *ex* channel-uuid "Q1" 2026))
        "the frozen grid is durable regardless of PV sync outcome")))

;; ---------------------------------------------------------------------------
;; ensure-collection! / sync-native-schedule! — the PV-facing collaborators,
;; stubbed via with-redefs since they call backends.pseudovision.client
;; directly rather than through the components map (same pattern
;; tunabrain_scheduling_test.clj uses for tb/json-post!).
;; ---------------------------------------------------------------------------

(deftest ensure-collection-reuses-existing-by-name
  (with-redefs [pv/get-collections (fn [_] [{:id 5 :name "auto:series:42"}])
                pv/create-collection! (fn [_ _] (throw (ex-info "should not create" {})))]
    (is (= 5 (orch/ensure-collection! ::pv {:name "auto:series:42" :kind :show :show-id 42})))))

(deftest ensure-collection-creates-when-absent
  (let [created (atom nil)]
    (with-redefs [pv/get-collections (fn [_] [])
                  pv/create-collection! (fn [_ data] (reset! created data) {:id 77})]
      (is (= 77 (orch/ensure-collection! ::pv {:name "auto:category:sitcom:channel:hua"
                                               :kind :category :category "sitcom"
                                               :channel-tag "channel:hua"})))
      (is (= "smart" (:kind @created)))
      (is (= {:category "sitcom" :channel-tag "channel:hua"} (get-in @created [:config :query]))))))

(def sync-grid
  {:channel "Classic Comedy"
   :strips [{:strip_id "prime" :days "weekdays" :start "17:00" :end "17:30"
             :content {:media_id "series:42" :strategy "sequential"}}]})

(deftest sync-native-schedule-full-round-trip
  (let [added-slots (atom [])
        attached    (atom nil)
        rebuilt     (atom false)
        deleted     (atom nil)]
    (with-redefs [pv/get-collections    (fn [_] [{:id 5 :name "auto:series:42"}])
                  pv/get-playout        (fn [_ _] {:schedule-id 3})
                  pv/create-schedule!   (fn [_ data] {:id 9 :name (:name data)})
                  pv/add-slot!          (fn [_ sched-id slot] (swap! added-slots conj [sched-id slot]) {})
                  pv/attach-schedule!   (fn [_ ch-id sched-id] (reset! attached [ch-id sched-id]) {})
                  pv/rebuild-playout!   (fn [_ _ _] (reset! rebuilt true) {})
                  pv/delete-schedule!   (fn [_ id] (reset! deleted id) {})]
      (let [result (orch/sync-native-schedule! ::pv 42 sync-grid "channel:classic-comedy")]
        (is (= 9 (:schedule-id result)))
        (is (= 1 (:slot-count result)))
        (is (= 1 (count @added-slots)))
        (is (= [42 9] @attached))
        (is @rebuilt)
        (is (= 3 @deleted) "the previously-attached schedule is cleaned up")))))

;; ---------------------------------------------------------------------------
;; propose-grid-via-daypart-candidates! — split round trip
;; (DURATION_AWARE_SCHEDULING.md §4.3, Option A)
;;
;; This is an alternative :propose-grid implementation with the SAME calling
;; contract as tb/propose-quarterly-grid!, so it's tested directly here
;; (stubbing the tb/* HTTP wrappers it calls internally via with-redefs, same
;; pattern as tunabrain_scheduling_test.clj) rather than only through
;; run-quarterly!'s component injection.
;; ---------------------------------------------------------------------------

(def two-block-skeleton
  {:channel "Classic Comedy"
   :blocks [{:name "daytime" :start "06:00" :end "17:00" :role "reruns"
             :genre_focus ["sitcom"]}
            {:name "prime" :start "17:00" :end "22:00" :role "movie night"
             :genre_focus ["movie"]}]})

(def profile-with-histograms
  (assoc profile
         :tag_runtime_histograms
         [{:tag "genre:sitcom"
           :buckets [{:label "15-30min" :min_minutes 15 :max_minutes 30 :item_count 200}]}
          {:tag "genre:movie"
           :buckets [{:label "90-105min" :min_minutes 90 :max_minutes 105 :item_count 12}]}]))

(defn- stub-tb-post [response]
  (fn [_client _path _payload & _] response))

(deftest propose-grid-via-daypart-candidates-calls-skeleton-then-strip-fill-per-block
  (let [strip-fill-calls (atom [])]
    (with-redefs [tb/json-post!
                  (fn [_client path payload & _]
                    (cond
                      (= path "/api/scheduling/propose-daypart-skeleton")
                      {:skeleton two-block-skeleton :cost_estimate {}}

                      (= path "/api/scheduling/propose-strip-fill")
                      (do
                        (swap! strip-fill-calls conj payload)
                        {:strips [{:strip_id (str (get-in payload [:block :name]) "-0")
                                  :days "weekdays" :start "00:00" :end "01:00"
                                  :content {:media_id "random:sitcom" :strategy "random"}}]
                         :cost_estimate {}})

                      :else (throw (ex-info "unexpected path" {:path path}))))]
      (let [result (orch/propose-grid-via-daypart-candidates!
                    ::tunabrain
                    {:channel channel-spec :quarter "Q1" :year 2026
                     :catalog-profile profile-with-histograms})]
        (testing "one strip-fill call per daypart block, in order"
          (is (= 2 (count @strip-fill-calls)))
          (is (= "daytime" (get-in (first @strip-fill-calls) [:block :name])))
          (is (= "prime" (get-in (second @strip-fill-calls) [:block :name]))))
        (testing "each block's call carries a duration-feasible candidate menu
                  computed from ITS OWN genre_focus"
          (let [daytime-candidates (:candidates (first @strip-fill-calls))
                prime-candidates   (:candidates (second @strip-fill-calls))]
            (is (seq daytime-candidates))
            (is (every? #(= "sitcom" (:category %))
                       (mapcat :slots daytime-candidates)))
            (is (seq prime-candidates))
            (is (every? #(= "movie" (:category %))
                       (mapcat :slots prime-candidates)))))
        (testing "prior_strips accumulates across blocks"
          (is (= [] (:prior_strips (first @strip-fill-calls))))
          (is (= 1 (count (:prior_strips (second @strip-fill-calls))))
              "the daytime block's strip is prior context for the prime block"))
        (testing "the assembled grid carries both blocks' strips and the skeleton"
          (is (= 2 (count (:strips (:grid result)))))
          (is (= two-block-skeleton (:skeleton result)))
          (is (= "success" (:status result)))
          (is (empty? (:warnings result))))))))

(deftest propose-grid-via-daypart-candidates-warns-on-empty-daypart
  (with-redefs [tb/json-post!
                (fn [_client path _payload & _]
                  (cond
                    (= path "/api/scheduling/propose-daypart-skeleton")
                    {:skeleton two-block-skeleton :cost_estimate {}}
                    (= path "/api/scheduling/propose-strip-fill")
                    {:strips [] :cost_estimate {}}
                    :else (throw (ex-info "unexpected path" {:path path}))))]
    (let [result (orch/propose-grid-via-daypart-candidates!
                  ::tunabrain
                  {:channel channel-spec :quarter "Q1" :year 2026
                   :catalog-profile profile-with-histograms})]
      (is (= 2 (count (:warnings result))))
      (is (= "partial" (:status result))))))

(deftest propose-grid-via-daypart-candidates-works-as-run-quarterly-propose-grid
  (testing "drop-in compatibility with run-quarterly!'s :propose-grid component"
    (with-redefs [tb/json-post!
                  (fn [_client path _payload & _]
                    (cond
                      (= path "/api/scheduling/propose-daypart-skeleton")
                      {:skeleton {:channel "Classic Comedy" :blocks []} :cost_estimate {}}
                      :else (throw (ex-info "unexpected path" {:path path}))))]
      (let [comps (base-components {:propose-grid orch/propose-grid-via-daypart-candidates!
                                    :repair-grid (fn [_ _] (throw (ex-info "should not repair" {})))})
            out (orch/run-quarterly! comps channel-spec "Q1" 2026
                                     :default-media-id "random:sitcom")]
        ;; An empty-blocks skeleton produces an empty-strip grid; with
        ;; default_content present (via :default-media-id) there are no
        ;; uncovered intervals either, so it's fully feasible and freezes
        ;; without a repair round.
        (is (= "ok" (:feasibility-status out)))
        (is (= 0 (:repairs out)))))))
