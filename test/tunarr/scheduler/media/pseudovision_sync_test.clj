(ns tunarr.scheduler.media.pseudovision-sync-test
  (:require [clojure.test :refer [deftest is testing]]
            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.media.pseudovision-sync :as pv-sync]))

;; ---
;; Minimal Catalog reify for sync-item-tags!.
;;
;; pv-sync/sync-item-tags! only calls two Catalog methods:
;; get-media-tags and get-media-categories. The Catalog protocol
;; however is large (~50 methods) and reify requires every protocol
;; method to be implemented. This reify returns sensible defaults for
;; the unused methods and throws if any of them is actually invoked
;; during a test — a runtime tripwire that signals a test is touching
;; a code path it didn't mean to.
;; ---

(defn- tripwire-impl
  "Throw a recognizable error so a test author notices if a stub is
   hit unexpectedly. Use as a method body in the Catalog reify."
  [m]
  (throw (ex-info (str "Catalog stub tripwire: " m " called; test should not have invoked it")
                  {})))

(defn- catalog-with
  "Build a Catalog whose only populated methods are
   get-media-tags and get-media-categories."
  [items-by-id]
  (let [by-id (into {} items-by-id)]
    (reify catalog/Catalog
      ;; --- populated ---
      (get-media-tags [_ media-id]
        (get-in by-id [media-id :base-tags]))
      (get-media-categories [_ media-id]
        (get-in by-id [media-id :categories]))
      ;; --- tripwires (shouldn't be reached) ---
      (add-media! [_ _] (tripwire-impl "add-media!"))
      (add-media-batch! [_ _] (tripwire-impl "add-media-batch!"))
      (get-media [_] (tripwire-impl "get-media"))
      (get-media-by-id [_ _] (tripwire-impl "get-media-by-id"))
      (get-media-by-library-id [_ _] (tripwire-impl "get-media-by-library-id"))
      (get-media-by-library [_ _] (tripwire-impl "get-media-by-library"))
      (get-media-by-kind [_ _ _] (tripwire-impl "get-media-by-kind"))
      (get-filler-items [_ _] (tripwire-impl "get-filler-items"))
      (count-media-by-kind [_ _] (tripwire-impl "count-media-by-kind"))
      (search-media-by-library-id [_ _ _] (tripwire-impl "search-media-by-library-id"))
      (get-tags [_] (tripwire-impl "get-tags"))
      (get-channels [_] (tripwire-impl "get-channels"))
      (get-genres [_] (tripwire-impl "get-genres"))
      (add-media-tags! [_ _ _] (tripwire-impl "add-media-tags!"))
      (set-media-tags! [_ _ _] (tripwire-impl "set-media-tags!"))
      (delete-media-tags! [_ _ _] (tripwire-impl "delete-media-tags!"))
      (update-channels! [_ _] (tripwire-impl "update-channels!"))
      (update-libraries! [_ _] (tripwire-impl "update-libraries!"))
      (add-media-channels! [_ _ _] (tripwire-impl "add-media-channels!"))
      (add-media-genres! [_ _ _] (tripwire-impl "add-media-genres!"))
      (add-media-taglines! [_ _ _] (tripwire-impl "add-media-taglines!"))
      (get-media-by-channel [_ _] (tripwire-impl "get-media-by-channel"))
      (get-media-by-tag [_ _] (tripwire-impl "get-media-by-tag"))
      (get-media-by-genre [_ _] (tripwire-impl "get-media-by-genre"))
      (get-media-process-timestamps [_ _] (tripwire-impl "get-media-process-timestamps"))
      (get-tag-samples [_] (tripwire-impl "get-tag-samples"))
      (delete-tag! [_ _] (tripwire-impl "delete-tag!"))
      (rename-tag! [_ _ _] (tripwire-impl "rename-tag!"))
      (batch-rename-tags! [_ _] (tripwire-impl "batch-rename-tags!"))
      (update-process-timestamp! [_ _ _] (tripwire-impl "update-process-timestamp!"))
      (delete-process-timestamp! [_ _ _] (tripwire-impl "delete-process-timestamp!"))
      (delete-library-process-timestamps! [_ _ _] (tripwire-impl "delete-library-process-timestamps!"))
      (close-catalog! [_] (tripwire-impl "close-catalog!"))
      (get-media-category-values [_ _ _] (tripwire-impl "get-media-category-values"))
      (add-media-category-value! [_ _ _ _ _] (tripwire-impl "add-media-category-value!"))
      (add-media-category-values! [_ _ _ _] (tripwire-impl "add-media-category-values!"))
      (set-media-category-values! [_ _ _ _] (tripwire-impl "set-media-category-values!"))
      (delete-media-category-value! [_ _ _ _] (tripwire-impl "delete-media-category-value!"))
      (delete-media-category-values! [_ _ _] (tripwire-impl "delete-media-category-values!"))
      (purge-category-value! [_ _ _] (tripwire-impl "purge-category-value!"))
      (get-episodes-by-series [_ _] (tripwire-impl "get-episodes-by-series"))
      (get-episode [_ _ _ _] (tripwire-impl "get-episode"))
      (get-effective-tags [_ _] (tripwire-impl "get-effective-tags"))
      (get-effective-categories [_ _] (tripwire-impl "get-effective-categories"))
      (get-library-id [_ _] (tripwire-impl "get-library-id"))
      (enrich-media-with-timestamps [_ m] (tripwire-impl "enrich-media-with-timestamps") m)
      (get-all-dimensions [_] (tripwire-impl "get-all-dimensions"))
      (get-dimension-values [_ _] (tripwire-impl "get-dimension-values"))
      (get-media-by-category-value [_ _ _] (tripwire-impl "get-media-by-category-value"))
      (get-media-context [_ _] (tripwire-impl "get-media-context"))
      (set-media-context! [_ _ _] (tripwire-impl "set-media-context!"))
      (delete-media-context! [_ _] (tripwire-impl "delete-media-context!")))))

;; ---
;; Test fixture: a media item with the minimum shape `sync-item-tags!`
;; needs. The Catalog reify only inspects `::media/id`.
;; ---

(defn- item [id base-tags categories]
  {::media/id id ::media/name (str "Item " id)
   ::media/parent-id nil
   ::media/library-id 30
   ::media/season-number nil
   ::media/episode-number nil
   ::media/kid-friendly? false
   ::media/subtitles? false
   ::media/community-rating nil
   ::media/critic-rating nil
   ::media/overview nil
   ::media/production-year nil
   ::media/premiere nil
   ::media/media-type "series"})

;; ---
;; Helper: redef the three PV client functions the sync uses
;; (`pv/add-tags!`, `pv/delete-tag!`, `pv/get-tags`) for the duration
;; of the body. Returns a map with `:added` and `:removed` atoms that
;; accumulate every call. `current-tags` is what `pv/get-tags` returns.
;; ---

(defn- with-stubbed-pv
  [current-tags body-fn]
  (let [added   (atom [])
        removed (atom [])
        added-tags   (atom [])  ;; flattened tag strings only
        removed-tags (atom [])  ;; flattened tag strings only
        add-tags!   (fn [_ pv-item-id tags]
                      (swap! added conj {:pv-item-id pv-item-id :tags (vec tags)})
                      (swap! added-tags into (vec tags))
                      {:item-id pv-item-id :tags-added tags})
        delete-tag! (fn [_ pv-item-id tag]
                      (swap! removed conj {:pv-item-id pv-item-id :tag tag})
                      (swap! removed-tags conj tag)
                      nil)
        get-tags    (fn [_ _] current-tags)]
    (with-redefs-fn
      {#'tunarr.scheduler.backends.pseudovision.client/add-tags!   add-tags!
       #'tunarr.scheduler.backends.pseudovision.client/delete-tag! delete-tag!
       #'tunarr.scheduler.backends.pseudovision.client/get-tags    get-tags}
      (fn []
        (body-fn {:added added :removed removed
                  :added-tags added-tags :removed-tags removed-tags})))))

;; ---
;; Tests
;; ---

(deftest reconcile-noop-when-sets-match
  (testing "desired == current: no adds, no removes, :unchanged reports the size"
    (let [cat (catalog-with {"show-1" {:base-tags  [:comedy]
                                      :categories {:channel [:goldenreels]}}})
          it  (item "show-1" [:comedy] {:channel [:goldenreels]})]
      (with-stubbed-pv ["comedy" "channel:goldenreels"]
        (fn [{:keys [added removed]}]
          (let [result (pv-sync/sync-item-tags! {} 52238 cat it)]
            (is (true? (:synced result)))
            (is (= [] @added))
            (is (= [] @removed))
            (is (= #{"comedy" "channel:goldenreels"} (set (:tags result))))
            (is (= 2 (:unchanged result)))
            (is (= [] (:added result)))
            (is (= [] (:removed result)))))))))

(deftest reconcile-removes-stale-tags
  (testing "Stale channel:goldenreels is in PV but not in catalog → it is removed"
    ;; Tiger and Dragon scenario: catalog now has channel:nippon, PV
    ;; still has the old channel:goldenreels/hua/spotlight tags.
    (let [cat (catalog-with {"show-1" {:base-tags  [:comedy :drama]
                                      :categories {:channel     [:nippon]
                                                   :freshness  [:modern]
                                                   :audience   [:adult :teen]}}})
          it  (item "show-1" [:comedy :drama]
                    {:channel    [:nippon]
                     :freshness  [:modern]
                     :audience   [:adult :teen]})]
      (with-stubbed-pv ["comedy" "drama" "channel:goldenreels" "channel:hua"
                        "channel:nippon" "channel:spotlight" "freshness:modern"
                        "audience:adult" "audience:teen"]
        (fn [{:keys [added removed added-tags removed-tags]}]
          (let [result (pv-sync/sync-item-tags! {} 52238 cat it)]
            (is (true? (:synced result)))
            (is (= [] added-tags)
                "no new tags to add (everything we want is already in PV)")
            (is (= #{"channel:goldenreels" "channel:hua" "channel:spotlight"}
                   (set removed-tags))
                "stale channel tags get deleted")
            (is (= #{"channel:goldenreels" "channel:hua" "channel:spotlight"}
                   (set (:removed result))))
            (is (= 6 (:unchanged result)))))))))

(deftest reconcile-adds-new-tags
  (testing "Newly added channel:nippon is in catalog but not in PV → it is added"
    (let [cat (catalog-with {"show-1" {:base-tags  [:comedy]
                                      :categories {:channel [:nippon]}}})
          it  (item "show-1" [:comedy] {:channel [:nippon]})]
      (with-stubbed-pv ["comedy"]
        (fn [{:keys [added removed]}]
          (let [result (pv-sync/sync-item-tags! {} 52238 cat it)]
            (is (true? (:synced result)))
            (is (= [{:pv-item-id 52238 :tags ["channel:nippon"]}] @added))
            (is (= [] @removed))))))))

(deftest reconcile-adds-and-removes-together
  (testing "Channel changed from goldenreels to nippon: removes old, adds new"
    (let [cat (catalog-with {"show-1" {:base-tags  [:comedy]
                                      :categories {:channel [:nippon]}}})
          it  (item "show-1" [:comedy] {:channel [:nippon]})]
      (with-stubbed-pv ["comedy" "channel:goldenreels" "channel:spotlight"]
        (fn [{:keys [added removed added-tags removed-tags]}]
          (let [result (pv-sync/sync-item-tags! {} 52238 cat it)]
            (is (true? (:synced result)))
            (is (= #{"channel:goldenreels" "channel:spotlight"} (set removed-tags)))
            (is (= ["channel:nippon"] added-tags))
            (is (= ["channel:nippon"] (:added result)))
            (is (= 1 (:unchanged result)))))))))

(deftest reconcile-empty-desired-clears-pv-tags
  (testing "Catalog has no tags at all → every PV tag is deleted"
    (let [cat (catalog-with {"show-1" {:base-tags [] :categories {}}})
          it  (item "show-1" [] {})]
      (with-stubbed-pv ["comedy" "channel:goldenreels"]
        (fn [{:keys [added removed added-tags removed-tags]}]
          (let [result (pv-sync/sync-item-tags! {} 52238 cat it)]
            (is (true? (:synced result)))
            (is (= [] added-tags))
            (is (= #{"comedy" "channel:goldenreels"} (set removed-tags)))
            (is (= #{"comedy" "channel:goldenreels"} (set (:removed result))))
            (is (= [] (:tags result)))
            (is (= 0 (:unchanged result)))))))))

(deftest reconcile-empty-current-bootstraps-tags
  (testing "PV has no tags yet → all desired tags are added (initial sync)"
    (let [cat (catalog-with {"show-1" {:base-tags  [:comedy :drama]
                                      :categories {:channel [:nippon]}}})
          it  (item "show-1" [:comedy :drama] {:channel [:nippon]})]
      (with-stubbed-pv []
        (fn [{:keys [added removed]}]
          (let [result (pv-sync/sync-item-tags! {} 52238 cat it)]
            (is (true? (:synced result)))
            (is (= [] @removed))
            (is (= 1 (count @added))
                "single batched add-tags! call with all three new tags")
            (is (= #{"comedy" "drama" "channel:nippon"}
                   (set (get-in (first @added) [:tags]))))))))))

(deftest reconcile-flattens-dimensions-correctly
  (testing "All dimension values become `dim:value` strings; values from all dimensions included"
    (let [cat (catalog-with {"show-1" {:base-tags  []
                                      :categories {:channel    [:nippon]
                                                   :freshness  [:modern]
                                                   :audience   [:adult :teen]
                                                   :time-slot  [:late-night]}}})
          it  (item "show-1" [] {:channel   [:nippon]
                                 :freshness [:modern]
                                 :audience  [:adult :teen]
                                 :time-slot [:late-night]})]
      (with-stubbed-pv []
        (fn [{:keys [added removed]}]
          (let [result (pv-sync/sync-item-tags! {} 52238 cat it)]
            (is (true? (:synced result)))
            (is (= #{"channel:nippon" "freshness:modern"
                     "audience:adult" "audience:teen"
                     "time-slot:late-night"}
                   (set (:tags result))))
            (is (= #{"channel:nippon" "freshness:modern"
                     "audience:adult" "audience:teen"
                     "time-slot:late-night"}
                   (set (get-in (first @added) [:tags]))))))))))

(deftest reconcile-pv-get-fails-treats-as-empty
  (testing "When pv/get-tags throws, the add path still runs (bootstraps from scratch)"
    (let [cat     (catalog-with {"show-1" {:base-tags  [:comedy]
                                          :categories {:channel [:nippon]}}})
          it      (item "show-1" [:comedy] {:channel [:nippon]})
          added   (atom [])
          removed (atom [])
          get-tags-throws (fn [_ _] (throw (ex-info "PV down" {})))
          add-stub (fn [_ pv-id tags]
                     (swap! added conj {:pv-item-id pv-id :tags (vec tags)})
                     {:item-id pv-id :tags-added tags})
          delete-stub (fn [_ pv-id tag]
                        (swap! removed conj {:pv-item-id pv-id :tag tag})
                        nil)]
      (with-redefs-fn
        {#'tunarr.scheduler.backends.pseudovision.client/get-tags    get-tags-throws
         #'tunarr.scheduler.backends.pseudovision.client/add-tags!   add-stub
         #'tunarr.scheduler.backends.pseudovision.client/delete-tag! delete-stub}
        (fn []
          (let [result (pv-sync/sync-item-tags! {} 52238 cat it)]
            (is (true? (:synced result)))
            (is (= [] @removed)
                "no deletes attempted when we can't see current state")
            (is (= 1 (count @added))
                "everything in desired is added (we treated current as empty)")
            (is (= #{"comedy" "channel:nippon"}
                   (set (get-in (first @added) [:tags]))))))))))

(deftest reconcile-filters-empty-and-nil-tags
  (testing "Empty-string tags from the catalog are filtered before push"
    (let [cat (catalog-with {"show-1" {:base-tags  [:comedy ""]
                                      :categories {:channel [:nippon]}}})
          it  (item "show-1" [:comedy ""] {:channel [:nippon]})]
      (with-stubbed-pv []
        (fn [{:keys [added removed added-tags removed-tags]}]
          (let [result (pv-sync/sync-item-tags! {} 52238 cat it)]
            (is (true? (:synced result)))
            (is (= #{"comedy" "channel:nippon"} (set (:tags result)))
                "empty-string base-tags are filtered, not sent as empty strings")
            (is (empty? (filter (fn [t] (= "" t)) (:tags result)))))))))

(deftest reconcile-pv-add-throws-is-reported
  (testing "If the underlying PV add fails, the function returns :error and :synced false"
    (let [cat (catalog-with {"show-1" {:base-tags  [:comedy]
                                      :categories {:channel [:nippon]}}})
          it  (item "show-1" [:comedy] {:channel [:nippon]})
          add-tags! (fn [& _] (throw (ex-info "PV 500" {})))]
      (with-redefs-fn
        {#'tunarr.scheduler.backends.pseudovision.client/get-tags    (fn [& _] [])
         #'tunarr.scheduler.backends.pseudovision.client/add-tags!   add-tags!
         #'tunarr.scheduler.backends.pseudovision.client/delete-tag! (fn [& _] nil)}
        (fn []
          (let [result (pv-sync/sync-item-tags! {} 52238 cat it)]
            (is (false? (:synced result)))
            (is (string? (:error result)))))))))
