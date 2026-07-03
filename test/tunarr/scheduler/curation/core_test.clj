(ns tunarr.scheduler.curation.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [tunarr.scheduler.curation.core :as curate]
            [tunarr.scheduler.jobs.throttler :as throttler]
            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.media.catalog :as catalog])
  (:import [java.util Date]))

;; ---------------------------------------------------------------------------
;; Test doubles
;; ---------------------------------------------------------------------------

(defrecord InlineThrottler []
  throttler/IThrottler
  (submit! [self f] (throttler/submit! self f nil []))
  (submit! [self f callback] (throttler/submit! self f callback []))
  (submit! [_ f callback args]
    (try
      (let [result (apply f args)]
        (when callback (callback {:result result})))
      (catch Throwable t
        (when callback (callback {:error t})))))
  (start! [self] self)
  (stop! [self] self))

(defrecord MockCatalog [media timestamp-log]
  catalog/Catalog
  (get-media-by-library [_ _library] media)
  (get-media-process-timestamps [_ m] (::media/process-timestamps m))
  (get-episodes-by-series [_ series-id]
    (filterv #(= series-id (::media/parent-id %)) media))
  (add-media-tags! [_ _media-id _tags] nil)
  (update-process-timestamp! [_ media-id process]
    (swap! timestamp-log conj {:media-id media-id :process process})))

(defrecord ContextCatalog [state]
  catalog/Catalog
  (get-media-context [_ media-id] (get-in @state [media-id]))
  (set-media-context! [_ media-id context]
    (swap! state assoc media-id context)))

;; ---------------------------------------------------------------------------
;; persist-context! — operator-edited context is sticky (handoff §5.3)
;; ---------------------------------------------------------------------------

(deftest persist-context-stores-auto-captured-context
  (testing "a response context is stored when the stored context is not operator-edited"
    (let [state   (atom {})
          catalog (->ContextCatalog state)]
      (curate/persist-context! catalog "m1" nil
                               {:summary "auto" :links [] :source "wikipedia"})
      (is (= {:summary "auto" :links [] :source "wikipedia" :operator-edited false}
             (get @state "m1"))
          "auto-captured context is stored with operator-edited false"))))

(deftest persist-context-does-not-clobber-operator-edits
  (testing "an operator-edited stored context is left untouched by a re-tag"
    (let [state   (atom {"m1" {:summary "operator correction" :operator-edited true}})
          catalog (->ContextCatalog state)]
      (curate/persist-context! catalog "m1"
                               {:summary "operator correction" :operator-edited true}
                               {:summary "fresh wikipedia" :source "wikipedia"})
      (is (= {:summary "operator correction" :operator-edited true}
             (get @state "m1"))
          "operator correction survives — the auto result is discarded"))))

(deftest persist-context-noop-when-no-response-context
  (testing "nothing is stored when the response carried no context"
    (let [state   (atom {})
          catalog (->ContextCatalog state)]
      (curate/persist-context! catalog "m1" nil nil)
      (is (= {} @state)))))

(defn- recording-reporter
  "A report-progress fn with the same semantics as the job runner's: maps
   replace the progress state, fns transform it."
  []
  (let [state (atom nil)]
    {:state state
     :report (fn [progress]
               (swap! state (fn [cur]
                              (if (fn? progress)
                                (progress (or cur {}))
                                progress))))}))

(defn- mk-media [id nm & {:keys [tagged-at type parent-id]}]
  (cond-> {::media/id id ::media/name nm}
    type      (assoc ::media/type type)
    parent-id (assoc ::media/parent-id parent-id)
    tagged-at (assoc ::media/process-timestamps
                     [{::media/process-name :process/tagging
                       ::media/last-run     tagged-at}])))

;; ---------------------------------------------------------------------------
;; retag-library-media!
;; ---------------------------------------------------------------------------

(deftest retag-library-reports-progress-and-waits-for-completion
  (testing "totals, completions, failures and skips are reported on the job"
    (let [items [(mk-media "m1" "Cowboy Bebop")
                 (mk-media "m2" "Trigun")
                 (mk-media "m3" "Akira" :tagged-at (Date.))]
          timestamp-log (atom [])
          catalog (->MockCatalog items timestamp-log)
          {:keys [state report]} (recording-reporter)
          result (with-redefs [curate/retag-media!
                               (fn [_brain _catalog m]
                                 (when (= "m2" (::media/id m))
                                   (throw (RuntimeException. "llm exploded")))
                                 :ok)]
                   (curate/retag-library-media! :brain catalog :movies (->InlineThrottler)
                                                :threshold 30
                                                :report-progress report))]
      ;; m3 was tagged recently, so it is skipped; m1 succeeds, m2 fails
      (is (= {:library :movies :total 2 :skipped 1} result))
      (is (= {:phase "tagging" :total 2 :skipped 1 :completed 1 :failed 1}
             @state))
      ;; only the successful item gets its process timestamp updated
      (is (= [{:media-id "m1" :process :process/tagging}]
             @timestamp-log)))))

(deftest retag-library-force-processes-everything
  (let [items [(mk-media "m1" "Cowboy Bebop" :tagged-at (Date.))
               (mk-media "m2" "Trigun" :tagged-at (Date.))]
        catalog (->MockCatalog items (atom []))
        {:keys [state report]} (recording-reporter)]
    (with-redefs [curate/retag-media! (fn [_ _ _] :ok)]
      (curate/retag-library-media! :brain catalog :movies (->InlineThrottler)
                                   :force true
                                   :report-progress report))
    (is (= {:phase "tagging" :total 2 :skipped 0 :completed 2 :failed 0}
           @state))))

(deftest retag-library-works-without-report-progress
  (let [items [(mk-media "m1" "Cowboy Bebop")]
        catalog (->MockCatalog items (atom []))]
    (with-redefs [curate/retag-media! (fn [_ _ _] :ok)]
      (is (= {:library :movies :total 1 :skipped 0}
             (curate/retag-library-media! :brain catalog :movies (->InlineThrottler)
                                          :force true))))))

;; ---------------------------------------------------------------------------
;; retag-library-episodes!
;; ---------------------------------------------------------------------------

(deftest retag-library-episodes-reports-progress
  (let [series (mk-media "s1" "Cowboy Bebop" :type :series)
        ep1    (mk-media "e1" "Asteroid Blues" :type :episode :parent-id "s1")
        ep2    (mk-media "e2" "Stray Dog Strut" :type :episode :parent-id "s1")
        catalog (->MockCatalog [series ep1 ep2] (atom []))
        {:keys [state report]} (recording-reporter)
        result (with-redefs [curate/retag-episode-with-special-flags!
                             (fn [_ _ _] :ok)]
                 (curate/tag-library-episodes! :brain catalog :shows (->InlineThrottler)
                                               :force true
                                               :report-progress report))]
    (is (= {:library :shows :total 2 :skipped 0} result))
    (is (= {:phase "episode-tagging" :total 2 :skipped 0 :completed 2 :failed 0}
           @state))))
