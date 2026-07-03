(ns tunarr.scheduler.media.pseudovision-autosync-test
  (:require [clojure.test :refer [deftest is testing]]
            [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.media.pseudovision-autosync :as autosync])
  (:import [java.util.concurrent LinkedBlockingQueue]))

(def reconcile-token @#'autosync/reconcile-token)
(def drain-into! @#'autosync/drain-into!)

(defn- drain-queue
  "Return all currently-queued signals as a vector."
  [worker]
  (let [^LinkedBlockingQueue q (:queue worker)
        acc (transient [])]
    (loop []
      (if-let [x (.poll q)]
        (do (conj! acc x) (recur))
        (persistent! acc)))))

(defn- test-worker
  "A worker whose threads are never started; we only inspect its queue."
  []
  (autosync/create {:pseudovision :stub :catalog :stub}))

;; A minimal inner catalog that records calls and returns sentinel values, so
;; we can confirm the decorator both delegates and enqueues.
(defn- recording-catalog [calls]
  (reify catalog/Catalog
    (get-media-tags [_ media-id] (swap! calls conj [:get-media-tags media-id]) [:existing])
    (add-media-tags! [_ media-id tags] (swap! calls conj [:add-media-tags! media-id tags]) :added)
    (set-media-tags! [_ media-id tags] (swap! calls conj [:set-media-tags! media-id tags]) :set)
    (delete-media-tags! [_ media-id tags] (swap! calls conj [:delete-media-tags! media-id tags]) :deleted)
    (add-media-category-values! [_ media-id category values]
      (swap! calls conj [:add-media-category-values! media-id category values]) :added-cats)
    (delete-media-category-values! [_ media-id category]
      (swap! calls conj [:delete-media-category-values! media-id category]) :deleted-cats)
    (delete-tag! [_ tag] (swap! calls conj [:delete-tag! tag]) :dropped)
    (rename-tag! [_ tag new-tag] (swap! calls conj [:rename-tag! tag new-tag]) :renamed)
    (batch-rename-tags! [_ pairs] (swap! calls conj [:batch-rename-tags! pairs]) :batch)
    (purge-category-value! [_ category value] (swap! calls conj [:purge category value]) :purged)))

(deftest wrap-catalog-returns-inner-when-no-worker
  (let [inner (recording-catalog (atom []))]
    (is (identical? inner (autosync/wrap-catalog inner nil)))))

(deftest item-mutations-delegate-and-mark-dirty
  (testing "each per-item tag/category mutation delegates AND enqueues its id"
    (let [calls  (atom [])
          worker (test-worker)
          cat    (autosync/wrap-catalog (recording-catalog calls) worker)]
      (is (= :added   (catalog/add-media-tags! cat "id1" [:a])))
      (is (= :set     (catalog/set-media-tags! cat "id2" [:b])))
      (is (= :deleted (catalog/delete-media-tags! cat "id3" [:c])))
      (is (= :added-cats   (catalog/add-media-category-values! cat "id4" :genre [:comedy])))
      (is (= :deleted-cats (catalog/delete-media-category-values! cat "id5" :genre)))
      ;; delegation happened
      (is (= [[:add-media-tags! "id1" [:a]]
              [:set-media-tags! "id2" [:b]]
              [:delete-media-tags! "id3" [:c]]
              [:add-media-category-values! "id4" :genre [:comedy]]
              [:delete-media-category-values! "id5" :genre]]
             @calls))
      ;; and each id was enqueued for sync
      (is (= ["id1" "id2" "id3" "id4" "id5"] (drain-queue worker))))))

(deftest reads-do-not-enqueue
  (testing "read-only calls delegate without marking anything dirty"
    (let [calls  (atom [])
          worker (test-worker)
          cat    (autosync/wrap-catalog (recording-catalog calls) worker)]
      (is (= [:existing] (catalog/get-media-tags cat "id1")))
      (is (= [[:get-media-tags "id1"]] @calls))
      (is (empty? (drain-queue worker))))))

(deftest global-tag-ops-request-reconcile
  (testing "ops that touch many items enqueue a reconcile, not per-item ids"
    (let [worker (test-worker)
          cat    (autosync/wrap-catalog (recording-catalog (atom [])) worker)]
      (catalog/delete-tag! cat :foo)
      (catalog/rename-tag! cat :old :new)
      (catalog/batch-rename-tags! cat [[:a :b]])
      (catalog/purge-category-value! cat :genre :bogus)
      (is (= [reconcile-token reconcile-token reconcile-token reconcile-token]
             (drain-queue worker))))))

(deftest drain-coalesces-duplicates
  (testing "a burst of dirty signals for the same id collapses to one entry"
    (let [q (LinkedBlockingQueue.)]
      (doseq [x ["a" "a" "b" "a" "c" "b"]] (.offer q x))
      (let [batch (drain-into! q 100 0)]
        (is (= #{"a" "b" "c"} (set batch)))
        (is (= 3 (count batch)))))))

(deftest drain-returns-nil-when-idle
  (testing "drain-into! returns nil (no batch) when nothing is queued"
    (is (nil? (drain-into! (LinkedBlockingQueue.) 1 0)))))

(deftest mark-helpers-are-noops-without-queue
  (testing "mark-dirty!/mark-reconcile! tolerate a nil/queue-less worker"
    (is (nil? (autosync/mark-dirty! {} "id")))
    (is (nil? (autosync/mark-reconcile! {})))))
