(ns tunarr.scheduler.scheduling.policy-test
  "Tests for the pure content-policy (watershed) enforcement helpers."
  (:require [clojure.test :refer [deftest testing is]]
            [tunarr.scheduler.scheduling.policy :as policy]))

;; "Adult content only after 10 PM": permitted 22:00 → 06:00 (crosses midnight),
;; forbidden the whole daytime in between.
(def adult-watershed
  {:dimension "audience" :value "adult" :allowed_from "22:00" :allowed_to "06:00"})

(def policy* {:watersheds [adult-watershed]})

(defn- slot [start end media & {:keys [filters] :or {filters []}}]
  {:start_time start :end_time end :media_id media
   :media_selection_strategy "sequential" :category_filters filters :notes []})

;; ---------------------------------------------------------------------------
;; Tag matching
;; ---------------------------------------------------------------------------

(deftest restricted?-matches-full-tag-and-bare-value
  (testing "the canonical dimension:value tag trips the watershed"
    (is (policy/restricted? adult-watershed ["audience:adult"]))
    (is (policy/restricted? adult-watershed ["channel:hbo" "audience:adult"])))
  (testing "case-insensitive"
    (is (policy/restricted? adult-watershed ["Audience:Adult"])))
  (testing "the bare value is a fallback (catalog stored 'adult' without a prefix)"
    (is (policy/restricted? adult-watershed ["adult"])))
  (testing "unrelated tags do not match"
    (is (not (policy/restricted? adult-watershed ["genre:comedy" "audience:teen"])))
    (is (not (policy/restricted? adult-watershed [])))))

(deftest watershed-tag-derivation
  (is (= "audience:adult" (policy/watershed-tag adult-watershed))))

;; ---------------------------------------------------------------------------
;; Grid-level violations (feasibility path)
;; ---------------------------------------------------------------------------

(defn- strip [id start end media & {:keys [filters] :or {filters []}}]
  {:strip_id id :days "weekdays" :start start :end end
   :content {:media_id media :category_filters filters :strategy "sequential"}})

(deftest grid-violations-flags-daytime-adult-strip
  (testing "an adult movie strip in the afternoon (Django at 18:00) is a violation"
    (let [grid {:strips [(strip "evening-movie" "18:00" "20:00" "movie:django")]}
          resolve-tags (constantly ["audience:adult"])
          v (policy/grid-violations grid policy* resolve-tags)]
      (is (= 1 (count v)))
      (is (re-find #"evening-movie" (first v)))
      (is (re-find #"18:00" (first v)))))
  (testing "detection also works from the strip's own category_filters (no catalog)"
    (let [grid {:strips [(strip "adult-block" "13:00" "15:00" "random:movies"
                                :filters ["audience:adult"])]}
          v (policy/grid-violations grid policy* (constantly nil))]
      (is (= 1 (count v))))))

(deftest grid-violations-allows-late-night-adult
  (testing "the same adult content after 22:00 is fine"
    (let [grid {:strips [(strip "late-movie" "22:30" "23:59" "movie:django")]}
          v (policy/grid-violations grid policy* (constantly ["audience:adult"]))]
      (is (empty? v))))
  (testing "content straddling the boundary is still flagged (some of it is daytime)"
    (let [grid {:strips [(strip "straddle" "21:00" "23:00" "movie:django")]}
          v (policy/grid-violations grid policy* (constantly ["audience:adult"]))]
      (is (= 1 (count v))))))

(deftest grid-violations-ignores-non-adult-content
  (let [grid {:strips [(strip "sitcom" "18:00" "19:00" "series:seinfeld")]}
        v (policy/grid-violations grid policy* (constantly ["audience:family"]))]
    (is (empty? v)))
  (testing "no policy ⇒ no violations"
    (is (empty? (policy/grid-violations {:strips [(strip "x" "12:00" "13:00" "movie:django")]}
                                        {:watersheds []}
                                        (constantly ["audience:adult"]))))))

;; ---------------------------------------------------------------------------
;; Slot-level enforcement (publish path)
;; ---------------------------------------------------------------------------

(def default-content {:media_id "random:filler" :strategy "random"})

(deftest enforce-slots-replaces-fully-forbidden-slot
  (testing "a daytime adult slot is wholly replaced with the default"
    (let [slots [(slot "2026-01-05T17:00:00" "2026-01-05T18:00:00" "movie:django"
                       :filters ["audience:adult"])]
          out   (policy/enforce-slots slots policy* default-content)]
      (is (= 1 (count out)))
      (is (= "random:filler" (:media_id (first out))))
      (is (= "2026-01-05T17:00:00" (:start_time (first out))))
      (is (= "2026-01-05T18:00:00" (:end_time (first out)))))))

(deftest enforce-slots-splits-straddling-slot
  (testing "a 21:00–23:00 adult slot splits: default until 22:00, kept after"
    (let [slots [(slot "2026-01-05T21:00:00" "2026-01-05T23:00:00" "movie:django"
                       :filters ["audience:adult"])]
          out   (policy/enforce-slots slots policy* default-content)]
      (is (= 2 (count out)))
      (let [[a b] out]
        (is (= ["2026-01-05T21:00:00" "2026-01-05T22:00:00"] [(:start_time a) (:end_time a)]))
        (is (= "random:filler" (:media_id a)))
        (is (= ["2026-01-05T22:00:00" "2026-01-05T23:00:00"] [(:start_time b) (:end_time b)]))
        (is (= "movie:django" (:media_id b)))
        (is (= ["audience:adult"] (:category_filters b)))))))

(deftest enforce-slots-leaves-non-adult-and-late-night-alone
  (testing "a non-adult daytime slot is untouched"
    (let [slots [(slot "2026-01-05T17:00:00" "2026-01-05T18:00:00" "series:seinfeld")]]
      (is (= slots (policy/enforce-slots slots policy* default-content)))))
  (testing "an adult slot fully within the allowed late-night window is untouched"
    (let [slots [(slot "2026-01-05T23:00:00" "2026-01-06T01:00:00" "movie:django"
                       :filters ["audience:adult"])]]
      (is (= slots (policy/enforce-slots slots policy* default-content))))))

(deftest enforce-slots-drops-forbidden-slot-when-no-default
  (testing "with no default content the forbidden portion becomes a gap"
    (let [slots [(slot "2026-01-05T17:00:00" "2026-01-05T18:00:00" "movie:django"
                       :filters ["audience:adult"])]
          out   (policy/enforce-slots slots policy* nil)]
      (is (empty? out)))))

(deftest enforce-slots-passthrough-without-policy
  (let [slots [(slot "2026-01-05T17:00:00" "2026-01-05T18:00:00" "movie:django"
                     :filters ["audience:adult"])]]
    (is (= slots (policy/enforce-slots slots {:watersheds []} default-content)))
    (is (= slots (policy/enforce-slots slots nil default-content)))))
