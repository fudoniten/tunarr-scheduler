(ns tunarr.scheduler.tunabrain-scheduling-test
  "Tests for the layered-grid scheduling client methods in
   tunarr.scheduler.tunabrain: the pure request builders and the HTTP wrappers
   (with the network call stubbed)."
  (:require [clojure.test :refer [deftest testing is]]
            [tunarr.scheduler.tunabrain :as tb]))

(def channel {:name "Classic Comedy" :description "24/7 vintage sitcoms"})

(def catalog-profile
  {:channel_scope "Classic Comedy" :total_items 900 :total_episodes 880 :movie_count 20
   :shows [] :genres [] :runtime_histogram [] :generated_at "2026-06-24T12:00:00"})

(def a-grid
  {:channel "Classic Comedy" :broadcast_day_start "06:00"
   :strips [{:strip_id "prime" :days "weekdays" :start "17:00" :end "18:00"
             :content {:media_id "series:seinfeld" :strategy "sequential"}}]
   :default_content {:media_id "random:sitcom" :strategy "random"}})

;; ---------------------------------------------------------------------------
;; Request builders (pure)
;; ---------------------------------------------------------------------------

(deftest ^:eftest/synchronized quarterly-grid-request-shape
  (let [req (tb/quarterly-grid-request
             {:channel channel :quarter "Q1" :year 2026
              :catalog-profile catalog-profile :quarterly-theme "New year, classic laughs"
              :default-media-id "random:sitcom"})]
    (testing "channel is reduced to {name, description}"
      (is (= {:name "Classic Comedy" :description "24/7 vintage sitcoms"} (:channel req))))
    (testing "snake_case wire keys"
      (is (= "Q1" (:quarter req)))
      (is (= 2026 (:year req)))
      (is (= catalog-profile (:catalog_profile req)))
      (is (= "New year, classic laughs" (:quarterly_theme req)))
      (is (= "random:sitcom" (:default_media_id req))))
    (testing "defaults"
      (is (= "06:00" (:broadcast_day_start req)))
      (is (= "balanced" (:cost_tier req)))
      (is (contains? req :strategic_guidance)))
    (testing "content_policy is omitted when there are no watersheds"
      (is (not (contains? req :content_policy)))
      (is (not (contains? (tb/quarterly-grid-request
                           {:channel channel :quarter "Q1" :year 2026
                            :catalog-profile catalog-profile :content-policy {:watersheds []}})
                          :content_policy))))
    (testing "content_policy is attached as a best-effort hint when watersheds exist"
      (let [pol {:watersheds [{:dimension "audience" :value "adult"
                               :allowed_from "22:00" :allowed_to "06:00"}]}
            r   (tb/quarterly-grid-request
                 {:channel channel :quarter "Q1" :year 2026
                  :catalog-profile catalog-profile :content-policy pol})]
        (is (= pol (:content_policy r)))))))

(deftest ^:eftest/synchronized repair-grid-request-shape
  (let [report {:horizon_start "2026-01-01" :horizon_end "2026-04-01"
                :overall_status "blocked" :strip_findings [] :overlaps []
                :uncovered_intervals [] :notes []}
        req (tb/repair-grid-request
             {:channel channel :catalog-profile catalog-profile
              :current-grid a-grid :feasibility-report report})]
    (is (= {:name "Classic Comedy" :description "24/7 vintage sitcoms"} (:channel req)))
    (is (= a-grid (:current_grid req)))
    (is (= report (:feasibility_report req)))
    (is (= "balanced" (:cost_tier req)))))

(deftest ^:eftest/synchronized monthly-overrides-request-shape
  (let [req (tb/monthly-overrides-request
             {:channel channel :month "2026-01" :grid a-grid
              :catalog-profile catalog-profile
              :planned-events ["Cheers marathon Saturday the 10th"]})]
    (is (= "2026-01" (:month req)))
    (is (= a-grid (:grid req)))
    (is (= ["Cheers marathon Saturday the 10th"] (:planned_events req)))
    (testing "planned_events defaults to an empty vector"
      (is (= [] (:planned_events (tb/monthly-overrides-request
                                  {:channel channel :month "2026-02" :grid a-grid
                                   :catalog-profile catalog-profile})))))))

;; ---------------------------------------------------------------------------
;; HTTP wrappers (json-post! stubbed)
;; ---------------------------------------------------------------------------

(defn- stub-post [capture response]
  (fn [_client path payload & _opts]
    (reset! capture {:path path :payload payload})
    response))

(deftest ^:eftest/synchronized propose-quarterly-grid-posts-and-returns
  (let [capture (atom nil)
        resp {:grid_id "g1" :status "ok" :grid a-grid :skeleton nil :warnings []
              :cost_estimate {} :suggested_next_steps []}]
    (with-redefs [tb/json-post! (stub-post capture resp)]
      (let [out (tb/propose-quarterly-grid! ::client
                                            {:channel channel :quarter "Q1" :year 2026
                                             :catalog-profile catalog-profile})]
        (is (= "/api/scheduling/propose-quarterly-grid" (:path @capture)))
        (is (= "Q1" (get-in @capture [:payload :quarter])))
        (is (= resp out))))))

(deftest ^:eftest/synchronized repair-quarterly-grid-posts-and-returns
  (let [capture (atom nil)
        resp {:grid_id "g2" :status "ok" :grid a-grid :changes [] :cost_estimate {}}]
    (with-redefs [tb/json-post! (stub-post capture resp)]
      (let [out (tb/repair-quarterly-grid! ::client
                                           {:channel channel :catalog-profile catalog-profile
                                            :current-grid a-grid :feasibility-report {}})]
        (is (= "/api/scheduling/repair-quarterly-grid" (:path @capture)))
        (is (= a-grid (:grid out)))))))

(deftest ^:eftest/synchronized propose-monthly-overrides-posts-and-returns
  (let [capture (atom nil)
        resp {:overrides_id "o1" :status "ok" :month "2026-01" :overrides []
              :warnings [] :cost_estimate {} :suggested_next_steps []}]
    (with-redefs [tb/json-post! (stub-post capture resp)]
      (let [out (tb/propose-monthly-overrides! ::client
                                               {:channel channel :month "2026-01" :grid a-grid
                                                :catalog-profile catalog-profile})]
        (is (= "/api/scheduling/propose-monthly-overrides" (:path @capture)))
        (testing "an empty overrides list is accepted as normal"
          (is (= [] (:overrides out))))))))

(deftest ^:eftest/synchronized propose-monthly-overrides-normalizes-scope
  (testing "explicit-null scope keys from Tunabrain are dropped so the closed
            OverrideScope contract (and storage) accept the date/days shape"
    (let [raw {:override_id "ovr-0"
               ;; Tunabrain emits every scope key, nulling the inapplicable ones.
               :scope {:date "2026-06-05" :days nil
                       :effective_start nil :effective_end nil}
               :start "20:00" :end "22:00"
               :content {:media_id "movie:abc" :strategy "specific"}
               :mode "replace" :priority 0 :note "Classic mystery movie night"}
          resp {:overrides_id "o1" :status "ok" :month "2026-06"
                :overrides [raw] :warnings [] :cost_estimate {}
                :suggested_next_steps []}]
      (with-redefs [tb/json-post! (fn [_client _path _payload & _] resp)]
        (let [out (tb/propose-monthly-overrides! ::client
                                                 {:channel channel :month "2026-06" :grid a-grid
                                                  :catalog-profile catalog-profile})
              scope (-> out :overrides first :scope)]
          (is (= {:date "2026-06-05"} scope))
          (is (not (contains? scope :days)))
          (is (not (contains? scope :effective_start)))
          (is (not (contains? scope :effective_end))))))))

(deftest ^:eftest/synchronized missing-grid-in-response-throws
  (with-redefs [tb/json-post! (stub-post (atom nil) {:grid_id "g" :status "ok" :grid nil})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (tb/propose-quarterly-grid! ::client
                                             {:channel channel :quarter "Q1" :year 2026
                                              :catalog-profile catalog-profile})))))
