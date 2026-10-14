(ns tunarr.scheduler.media.pseudovision-media-sync-test
  "PV→TS media sync used to copy PV's numeric :parent-id into the catalog,
   whose rows are keyed by remote-key, so every child failed
   media_parent_id_fkey (health report 2026-10-14: 855 errors a night), and
   episodes never got a season number (PV keeps it on the parent season)."
  (:require [clojure.test :refer [deftest is testing]]
            [tunarr.scheduler.media.pseudovision-media-sync :as sync]))

(def ^:private stubs
  [{:id 3 :kind "episode" :remote-key "ep-1"     :parent-id 2 :position 4}
   {:id 2 :kind "season"  :remote-key "season-2" :parent-id 1 :position 2}
   {:id 1 :kind "show"    :remote-key "show-1"}
   {:id 4 :kind "episode" :remote-key "ep-flat"  :parent-id 1 :position 7}
   {:id 5 :kind "movie"   :remote-key "movie-1"}])

(def ^:private by-id (into {} (map (juxt :id identity)) stubs))

(deftest catalog-overrides-test
  (testing "an episode under a season hangs off the show, with the season's number"
    (is (= {:parent-id "show-1" :season-number 2}
           (sync/catalog-overrides (by-id 3) by-id))))
  (testing "an episode directly under a show keeps the show as parent"
    (is (= {:parent-id "show-1"} (sync/catalog-overrides (by-id 4) by-id))))
  (testing "parent ids are translated to remote-keys"
    (is (= {:parent-id "show-1"} (sync/catalog-overrides (by-id 2) by-id))))
  (testing "top-level items are untouched"
    (is (= {} (sync/catalog-overrides (by-id 5) by-id)))))

(deftest sync-order-test
  (let [ordered (map :remote-key (sync/sync-order stubs by-id))]
    (testing "seasons are folded into episodes, not synced"
      (is (not (some #{"season-2"} ordered))))
    (testing "parents are synced before their children"
      (is (< (.indexOf ordered "show-1") (.indexOf ordered "ep-1")))
      (is (< (.indexOf ordered "show-1") (.indexOf ordered "ep-flat"))))))
