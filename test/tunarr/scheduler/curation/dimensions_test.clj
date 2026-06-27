(ns tunarr.scheduler.curation.dimensions-test
  (:require [clojure.test :refer [deftest is testing]]
            [tunarr.scheduler.curation.dimensions :as dimensions]
            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.media.memory-catalog]))

;; ---------------------------------------------------------------------------
;; config->allowed-values
;; ---------------------------------------------------------------------------

(deftest config->allowed-values-from-categories
  (testing "plain keyword and map-shaped category values are both normalized"
    (let [allowed (dimensions/config->allowed-values
                   {:categories {:time-slot {:values [:daytime :primetime]}
                                 :audience  {:values [{:value :kids :description "for children"}
                                                      {:value :adult}]}}})]
      (is (= #{:daytime :primetime} (:time-slot allowed)))
      (is (= #{:kids :adult} (:audience allowed))))))

(deftest config->allowed-values-channel-from-channels
  (testing "the channel dimension's vocabulary is the set of configured channel keys"
    (let [allowed (dimensions/config->allowed-values
                   {:channels {:spectrum {} :prime {} :true-north {}}})]
      (is (= #{:spectrum :prime :true-north} (:channel allowed)))))

  (testing "explicit :channel category and :channels are unioned"
    (let [allowed (dimensions/config->allowed-values
                   {:categories {:channel {:values [:spectrum]}}
                    :channels   {:prime {}}})]
      (is (= #{:spectrum :prime} (:channel allowed))))))

;; ---------------------------------------------------------------------------
;; value-allowed?
;; ---------------------------------------------------------------------------

(deftest value-allowed?-semantics
  (let [allowed {:channel #{:spectrum :prime}}]
    (is (dimensions/value-allowed? allowed :channel :spectrum))
    (is (not (dimensions/value-allowed? allowed :channel :spectum)))
    (testing "dimensions with no configured vocabulary are permissive"
      (is (dimensions/value-allowed? allowed :freshness :anything)))))

;; ---------------------------------------------------------------------------
;; filter-dimensions
;; ---------------------------------------------------------------------------

(defn- sel [value] {::media/category-value value ::media/rationale ""})

(deftest filter-dimensions-drops-invalid-values
  (let [allowed     {:channel   #{:spectrum :prime}
                     :time-slot #{:daytime :primetime}}
        dimensions  {:channel   [(sel :spectrum) (sel :spectum) (sel :thriller)]
                     :time-slot [(sel :daytime)]}
        {:keys [dimensions rejected]} (dimensions/filter-dimensions allowed dimensions)]
    (is (= [(sel :spectrum)] (:channel dimensions)))
    (is (= [(sel :daytime)] (:time-slot dimensions)))
    (is (= #{{:dimension :channel :value :spectum}
             {:dimension :channel :value :thriller}}
           (set rejected)))))

(deftest filter-dimensions-passes-through-unconfigured-dimensions
  (testing "a dimension with no vocabulary is left untouched"
    (let [allowed    {:channel #{:spectrum}}
          dimensions {:mood [(sel :tense) (sel :whimsical)]}
          {:keys [dimensions rejected]} (dimensions/filter-dimensions allowed dimensions)]
      (is (= [(sel :tense) (sel :whimsical)] (:mood dimensions)))
      (is (empty? rejected)))))

;; ---------------------------------------------------------------------------
;; clean-catalog! (against the real in-memory catalog)
;; ---------------------------------------------------------------------------

(defn- seed-catalog!
  "Build an in-memory catalog seeded with the given
   {media-id {dimension [values]}} categorization map."
  [categorizations]
  (let [catalog (catalog/initialize-catalog! {:type :memory})]
    (doseq [[media-id dims] categorizations
            [dimension values] dims]
      (catalog/set-media-category-values!
       catalog media-id dimension
       (map (fn [v] {::media/category-value v ::media/rationale ""}) values)))
    catalog))

(deftest clean-catalog!-removes-invalid-values
  (let [catalog (seed-catalog! {"m1" {:channel [:spectrum :spectum]}
                                "m2" {:channel [:thriller]
                                      :time-slot [:daytime]}
                                "m3" {:channel [:prime]}})
        allowed {:channel   #{:spectrum :prime}
                 :time-slot #{:daytime :primetime}}
        result  (dimensions/clean-catalog! catalog allowed)]
    (testing "report counts the invalid values"
      (is (= 2 (:invalid-found result)))
      (is (= 2 (:values-removed result)))
      (is (false? (:dry-run result)))
      (is (= #{{:dimension "channel" :value "spectum" :usage-count 1}
               {:dimension "channel" :value "thriller" :usage-count 1}}
             (set (:removed result)))))
    (testing "invalid values are purged across all media, valid ones remain"
      (is (= [:spectrum] (catalog/get-media-category-values catalog "m1" :channel)))
      (is (= [] (catalog/get-media-category-values catalog "m2" :channel)))
      (is (= [:daytime] (catalog/get-media-category-values catalog "m2" :time-slot)))
      (is (= [:prime] (catalog/get-media-category-values catalog "m3" :channel))))))

(deftest clean-catalog!-dry-run-changes-nothing
  (let [catalog (seed-catalog! {"m1" {:channel [:spectrum :spectum]}})
        allowed {:channel #{:spectrum}}
        result  (dimensions/clean-catalog! catalog allowed :dry-run true)]
    (is (= 1 (:invalid-found result)))
    (is (= 0 (:values-removed result)))
    (is (true? (:dry-run result)))
    (testing "nothing is actually removed"
      (is (= #{:spectrum :spectum}
             (set (catalog/get-media-category-values catalog "m1" :channel)))))))

(deftest clean-catalog!-skips-unconfigured-dimensions
  (let [catalog (seed-catalog! {"m1" {:channel [:spectrum]
                                      :mood    [:invented]}})
        allowed {:channel #{:spectrum}}
        result  (dimensions/clean-catalog! catalog allowed)]
    (is (= ["mood"] (:skipped-dimensions result)))
    (is (= 0 (:values-removed result)))
    (testing "values in an unconfigured dimension are left alone"
      (is (= [:invented] (catalog/get-media-category-values catalog "m1" :mood))))))
