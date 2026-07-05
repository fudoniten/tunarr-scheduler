(ns tunarr.scheduler.media.syncing-catalog-context-test
  "Regression tests for SyncingCatalog's grounding-context delegation.

   The Pseudovision auto-sync decorator (SyncingCatalog) must implement the
   per-media grounding-context protocol methods. When it didn't, every
   /api/media-item/:id/context* request and the curation persist-context! path
   called a protocol method the record didn't implement, throwing
   AbstractMethodError. That Error escaped every (catch Exception ...) in the
   HTTP stack, so the client saw an opaque Jetty HTML 500 with nothing logged,
   and Tunabrain's returned grounding context was never persisted."
  (:require [clojure.test :refer [deftest is testing]]
            [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.media.pseudovision-autosync :as autosync]))

;; A minimal inner catalog implementing only the context methods (reify allows
;; partial protocol implementation; the delegation under test never touches the
;; others). Records calls so we can assert the wrapper forwards verbatim.
(defn- context-recording-catalog [calls store]
  (reify catalog/Catalog
    (get-media-context [_ media-id]
      (swap! calls conj [:get-media-context media-id])
      (get @store media-id))
    (set-media-context! [_ media-id context]
      (swap! calls conj [:set-media-context! media-id context])
      (swap! store assoc media-id context)
      nil)
    (delete-media-context! [_ media-id]
      (swap! calls conj [:delete-media-context! media-id])
      (swap! store dissoc media-id)
      nil)))

(defn- test-worker
  "An auto-sync worker whose threads are never started; we only inspect its
   queue to confirm context changes do not enqueue Pseudovision syncs."
  []
  (autosync/create {:pseudovision :stub :catalog :stub}))

(defn- queued-count [worker]
  (.size ^java.util.concurrent.LinkedBlockingQueue (:queue worker)))

(deftest syncing-catalog-delegates-context-methods
  (testing "get/set/delete-media-context! delegate straight through to inner"
    (let [calls  (atom [])
          store  (atom {})
          worker (test-worker)
          cat    (autosync/wrap-catalog (context-recording-catalog calls store) worker)]
      (is (instance? tunarr.scheduler.media.pseudovision_autosync.SyncingCatalog cat)
          "worker present -> catalog is actually wrapped")
      (is (nil? (catalog/get-media-context cat "m1")))
      (is (nil? (catalog/set-media-context! cat "m1" {:links ["http://x"] :operator-edited true})))
      (is (= {:links ["http://x"] :operator-edited true}
             (catalog/get-media-context cat "m1"))
          "value round-trips through the wrapper to inner")
      (is (nil? (catalog/delete-media-context! cat "m1")))
      (is (nil? (catalog/get-media-context cat "m1")))
      (is (= [[:get-media-context "m1"]
              [:set-media-context! "m1" {:links ["http://x"] :operator-edited true}]
              [:get-media-context "m1"]
              [:delete-media-context! "m1"]
              [:get-media-context "m1"]]
             @calls)
          "every call forwarded verbatim to inner, in order")
      (is (zero? (queued-count worker))
          "context is TS-internal grounding, not PV-synced state -> no sync enqueued"))))
