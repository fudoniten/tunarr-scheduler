(ns tunarr.scheduler.scheduling.candidates-test
  "Tests for propose-daypart-candidates (DURATION_AWARE_SCHEDULING.md §4.2)."
  (:require [clojure.test :refer [deftest testing is]]
            [tunarr.scheduler.scheduling.candidates :as sut]))

(defn- block [start end & {:keys [genre-focus]}]
  (cond-> {:name "prime" :start start :end end :role "movie night"}
    genre-focus (assoc :genre_focus genre-focus)))

(defn- histo [tag & buckets]
  {:tag tag :buckets (vec buckets)})

(defn- bucket [label min-m max-m count]
  {:label label :min_minutes min-m :max_minutes max-m :item_count count})

(deftest single-bucket-tiles-block-as-one-slot
  (testing "a 120min block with a single 90-105min bucket tiles as one slot,
            not fragmenting to match the bucket width exactly"
    (let [profile {:tag_runtime_histograms
                   [(histo "genre:movie" (bucket "90-105min" 90 105 12))]}
          [candidate] (sut/propose-daypart-candidates profile (block "20:00" "22:00"))]
      (is (= "movie-90-105min" (:layout_id candidate)))
      (is (= 12.0 (:weight candidate)))
      (is (= 1 (count (:slots candidate))))
      (is (= {:duration_minutes 120 :category "movie" :available_count 12}
             (first (:slots candidate)))))))

(deftest multiple-buckets-for-one-tag-produce-multiple-candidates
  (testing "a 4-hour block with two populated movie buckets produces two
            homogeneous candidates, the better-stocked one weighted higher"
    (let [profile {:tag_runtime_histograms
                   [(histo "genre:movie"
                           (bucket "90-105min" 90 105 12)
                           (bucket "180-195min" 180 195 1))]}
          candidates (sut/propose-daypart-candidates profile (block "18:00" "22:00"))]
      (is (= 2 (count candidates)))
      (is (= "movie-90-105min" (:layout_id (first candidates)))
          "higher-weight (better-stocked) candidate sorts first")
      (is (= 2 (count (:slots (first candidates))))
          "the 90-105min bucket tiles the 240min block as 2x120min slots")
      (is (= 1 (count (:slots (second candidates))))
          "the 180-195min bucket tiles the same block as a single 240min slot"))))

(deftest zero-item-count-buckets-are-excluded
  (testing "an empty bucket contributes no candidate"
    (let [profile {:tag_runtime_histograms
                   [(histo "genre:movie" (bucket "90-105min" 90 105 0))]}]
      (is (empty? (sut/propose-daypart-candidates profile (block "20:00" "21:30")))))))

(deftest genre-focus-narrows-candidate-pool
  (testing "a block with a genre_focus only offers candidates from matching tags"
    (let [profile {:tag_runtime_histograms
                   [(histo "genre:movie" (bucket "90-105min" 90 105 12))
                    (histo "genre:sitcom" (bucket "15-30min" 15 30 200))]}
          candidates (sut/propose-daypart-candidates
                      profile (block "20:00" "22:00" :genre-focus ["movie"]))]
      (is (= 1 (count candidates)))
      (is (= "movie-90-105min" (:layout_id (first candidates)))))))

(deftest genre-focus-with-no-matching-tag-falls-back-to-full-pool
  (testing "a block should never get an empty menu just because its focus
            didn't line up with tag naming"
    (let [profile {:tag_runtime_histograms
                   [(histo "genre:movie" (bucket "90-105min" 90 105 12))]}
          candidates (sut/propose-daypart-candidates
                      profile (block "20:00" "22:00" :genre-focus ["documentary"]))]
      (is (= 1 (count candidates)))
      (is (= "movie-90-105min" (:layout_id (first candidates)))))))

(deftest candidates-are-capped-and-sorted-by-weight-descending
  (testing "more than max-candidates-per-block populated buckets are capped,
            keeping the highest-weight (best-stocked) ones"
    (let [buckets (for [i (range 12)]
                    (bucket (str i "-bucket") (* i 15) (+ 15 (* i 15)) (inc i)))
          profile {:tag_runtime_histograms [(apply histo "genre:movie" buckets)]}
          candidates (sut/propose-daypart-candidates profile (block "20:00" "22:00"))]
      (is (= 8 (count candidates)))
      (is (apply >= (map :weight candidates))
          "sorted by weight descending")
      (is (= 12.0 (:weight (first candidates)))
          "the best-stocked bucket (item_count 12) is always kept"))))

(deftest cross-midnight-block-duration-is-computed-correctly
  (testing "a block crossing midnight (23:00-01:00, 120min) tiles correctly"
    (let [profile {:tag_runtime_histograms
                   [(histo "genre:movie" (bucket "90-105min" 90 105 12))]}
          [candidate] (sut/propose-daypart-candidates profile (block "23:00" "01:00"))]
      (is (= 1 (count (:slots candidate))))
      (is (= 120 (:duration_minutes (first (:slots candidate))))))))

(deftest no-histogram-data-yields-no-candidates
  (testing "a CatalogProfile with no tag_runtime_histograms at all (an older
            Pseudovision build) yields an empty menu, not an error — the
            caller falls back to unconstrained strip-fill"
    (is (empty? (sut/propose-daypart-candidates {} (block "20:00" "22:00"))))))
