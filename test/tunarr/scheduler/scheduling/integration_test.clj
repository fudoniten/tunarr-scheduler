(ns tunarr.scheduler.scheduling.integration-test
  "Tests for the Pseudovision boundary: kebab↔snake conversion, CatalogProfile
   and display labels (with the PV client stubbed)."
  (:require [clojure.test :refer [deftest testing is]]
            [taoensso.timbre :as log]
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
;; label-grid-content — resolve show ids to titles for operator views
;; ---------------------------------------------------------------------------

(def ^:private title-profile
  {:total_items 100 :total_episodes 80
   :shows [{:media_id "series:42" :title "Cheers"}
           {:media_id "series:7"  :title "Frasier"}]})

(deftest ^:eftest/synchronized label-grid-content-fills-show-titles
  (let [grid {:channel "Classic Comedy"
              :strips [{:strip_id "a" :days "weekdays" :start "17:00" :end "18:00"
                        :content {:media_id "series:42" :strategy "sequential"}}
                       {:strip_id "b" :days "weekdays" :start "18:00" :end "19:00"
                        :content {:media_id "random:comedy" :strategy "random"}}
                       {:strip_id "c" :days "weekends" :start "18:00" :end "19:00"
                        :content {:media_id "series:7" :strategy "sequential"
                                  :label "Operator's own name"}}
                       {:strip_id "d" :days "daily" :start "02:00" :end "03:00"
                        :content {:media_id "series:999" :strategy "sequential"}}]
              :default_content {:media_id "series:42" :strategy "random"}}
        out  (integ/label-grid-content grid title-profile)
        strip #(-> out :strips (nth %) :content)]
    (testing "an unlabelled series id gets the show title"
      (is (= "Cheers" (:label (strip 0)))))
    (testing "random:<category> is left untouched"
      (is (nil? (:label (strip 1)))))
    (testing "an existing operator label is not overwritten"
      (is (= "Operator's own name" (:label (strip 2)))))
    (testing "an id absent from the profile is left untouched"
      (is (nil? (:label (strip 3)))))
    (testing "default_content is labelled too"
      (is (= "Cheers" (-> out :default_content :label))))))

(deftest ^:eftest/synchronized label-grid-content-tolerates-sparse-grids
  (testing "a grid with no strips and no default_content is returned unchanged"
    (let [grid {:channel "X"}]
      (is (= grid (integ/label-grid-content grid title-profile)))))
  (testing "a profile with no shows leaves every id untouched"
    (let [grid {:channel "X"
                :strips [{:strip_id "a" :days "daily" :start "00:00" :end "01:00"
                          :content {:media_id "series:42" :strategy "sequential"}}]}]
      (is (nil? (-> (integ/label-grid-content grid {:total_items 0 :total_episodes 0})
                    :strips first :content :label))))))

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

(defn- capture-warnings
  "Run (f) with timbre warnings captured via a temporary appender; returns the
   vector of warn arg-lists (each the raw :vargs passed to log/warn)."
  [f]
  (let [warnings (atom [])]
    (log/with-merged-config
      {:appenders {:capture {:enabled?  true
                             :min-level :warn
                             :fn        (fn [{:keys [level vargs]}]
                                          (when (= :warn level)
                                            (swap! warnings conj (vec vargs))))}}}
      (f))
    @warnings))

(defn- ignored-filter-warned? [warnings]
  (some (fn [args]
          (some #(and (string? %) (re-find #"ignored the filter" %)) args))
        warnings))

(deftest ^:eftest/synchronized fetch-catalog-profile-warns-when-tag-filter-ignored
  (testing "a tag filter whose shows don't all carry the tag is flagged"
    ;; pv-catalog-aggregate's lone show carries channel:comedy, not channel:drama
    (let [warnings (capture-warnings
                    #(with-redefs [pv/get-catalog-aggregate (fn [_cfg _opts] pv-catalog-aggregate)]
                       (integ/fetch-catalog-profile ::cfg {:tag "channel:drama"})))]
      (is (ignored-filter-warned? warnings)
          "expected a warning when returned shows lack the requested channel tag")))
  (testing "a tag filter whose shows all carry the tag is not flagged"
    (let [warnings (capture-warnings
                    #(with-redefs [pv/get-catalog-aggregate (fn [_cfg _opts] pv-catalog-aggregate)]
                       (integ/fetch-catalog-profile ::cfg {:tag "channel:comedy"})))]
      (is (not (ignored-filter-warned? warnings)))))
  (testing "no shows in the aggregate means nothing to judge — no warning"
    (let [warnings (capture-warnings
                    #(with-redefs [pv/get-catalog-aggregate
                                   (fn [_cfg _opts] (dissoc pv-catalog-aggregate :shows))]
                       (integ/fetch-catalog-profile ::cfg {:tag "channel:drama"})))]
      (is (not (ignored-filter-warned? warnings))))))
