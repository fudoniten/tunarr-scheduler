(ns tunarr.scheduler.media.sync-test
  (:require [clojure.test :refer [deftest is testing]]
            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.media.collection :as collection]
            [tunarr.scheduler.media.sync :as sync]))

(defn- make-collection [items-by-library]
  (reify collection/MediaCollection
    (get-library-items [_ library]
      (get items-by-library library []))
    (close! [_] nil)))

(defn- make-catalog [added]
  (reify catalog/Catalog
    (add-media! [_ media]
      (swap! added conj media)
      nil)
    (add-media-batch! [_ media-items]
      (swap! added concat media-items)
      nil)
    (get-media [_] @added)
    (get-media-by-id [_ _] [])
    (get-media-by-library-id [_ _] [])
    (add-media-tags! [_ _ _] nil)
    (add-media-channels! [_ _ _] nil)
    (add-media-genres! [_ _ _] nil)
    (get-media-by-channel [_ _] [])
    (get-media-by-tag [_ _] [])
    (get-media-by-genre [_ _] [])
    (close-catalog! [_] nil)))

(deftest rescan-libraries-imports-media-test
  (let [added (atom [])
        collection (make-collection {:movies [{::media/id "1" ::media/name "Movie"}
                                              {::media/id "2" ::media/name "Another"}]})
        catalog (make-catalog added)
        result (sync/rescan-library! collection catalog {:library :movies
                                                         :report-progress (fn [_] nil)})]
    (is (= 2 (get-in result [:result :count])))
    (is (= :movies (:library result)))
    (is (= 2 (count @added)))))

(deftest rescan-libraries-requires-input-test
  (let [collection (make-collection {})
        catalog (make-catalog (atom []))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (sync/rescan-library! collection catalog {:report-progress (fn [_] nil)})))))
