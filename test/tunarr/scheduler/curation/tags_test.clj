(ns tunarr.scheduler.curation.tags-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [tunarr.scheduler.curation.tags :as tags]
            [tunarr.scheduler.media.catalog :as catalog]))

;; Mock catalog for testing
(defrecord MockCatalog [state]
  catalog/Catalog
  (get-tags [_]
    (get @state :tags []))
  (rename-tag! [_ tag new-tag]
    (swap! state update :tags
           (fn [tags]
             (mapv #(if (= (name %) tag) (keyword new-tag) %) tags)))
    (swap! state update :rename-log (fnil conj []) {:tag tag :new-tag new-tag}))
  (batch-rename-tags! [self tag-pairs]
    (doseq [[tag new-tag] tag-pairs]
      (catalog/rename-tag! self tag new-tag)))
  (delete-tag! [_ tag]
    (swap! state update :tags
           (fn [tags] (vec (remove #(= (name %) tag) tags))))
    (swap! state update :delete-log (fnil conj []) {:tag tag}))
  ;; Stub implementations for other methods
  (add-media! [_ media] nil)
  (add-media-batch! [_ media-items] nil)
  (get-media [_] [])
  (get-media-by-id [_ media-id] [])
  (get-media-by-library-id [_ library-id] [])
  (get-media-by-library [_ library] [])
  (get-media-tags [_ media-id] [])
  (add-media-tags! [_ media-id tags] nil)
  (set-media-tags! [_ media-id tags] nil)
  (update-channels! [_ channels] nil)
  (update-libraries! [_ libraries] nil)
  (add-media-channels! [_ media-id channels] nil)
  (add-media-genres! [_ media-id genres] nil)
  (add-media-taglines! [_ media-id taglines] nil)
  (get-media-by-channel [_ channel] [])
  (get-media-by-tag [_ tag] [])
  (get-media-by-genre [_ genre] [])
  (get-media-process-timestamps [_ media] [])
  (get-tag-samples [_] [])
  (update-process-timestamp! [_ media-id process] nil)
  (close-catalog! [_] nil)
  (get-media-category-values [_ media-id category] [])
  (add-media-category-value! [_ media-id category value] nil)
  (add-media-category-values! [_ media-id category values] nil)
  (set-media-category-values! [_ media-id category values] nil)
  (get-media-categories [_ media-id] {})
  (delete-media-category-value! [_ media-id category value] nil)
  (delete-media-category-values! [_ media-id category] nil))

;; Tag rule loading tests
(deftest load-tag-rule-merge-test
  (testing "load-tag-rule parses merge rules"
    (let [rule (tags/load-tag-rule ["old-tag" "merge" "new-tag"])]
      (is (= :rename (:type rule)))
      (is (= "old-tag" (:tag rule)))
      (is (= "new-tag" (:new-tag rule))))))

(deftest load-tag-rule-rename-test
  (testing "load-tag-rule parses rename rules"
    (let [rule (tags/load-tag-rule ["old-tag" "rename" "new-tag"])]
      (is (= :rename (:type rule)))
      (is (= "old-tag" (:tag rule)))
      (is (= "new-tag" (:new-tag rule))))))

(deftest load-tag-rule-drop-test
  (testing "load-tag-rule parses drop rules"
    (let [rule (tags/load-tag-rule ["unwanted-tag" "drop"])]
      (is (= :drop (:type rule)))
      (is (= "unwanted-tag" (:tag rule))))))

(deftest load-tag-rule-no-op-test
  (testing "load-tag-rule parses no-op rules (empty action)"
    (let [rule (tags/load-tag-rule ["some-tag" ""])]
      (is (= :no-op (:type rule)))
      (is (= "some-tag" (:tag rule))))))

(deftest load-tag-rule-invalid-test
  (testing "load-tag-rule throws on invalid rule type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                         #"unrecognized or missing rule type"
                         (tags/load-tag-rule ["tag" "invalid-action"])))))

;; Tag cleaning tests
(deftest clean-tag-lowercase-test
  (testing "clean-tag converts to lowercase"
    (is (= "action" (tags/clean-tag :Action)))
    (is (= "science_fiction" (tags/clean-tag :Science-Fiction)))))

(deftest clean-tag-special-chars-test
  (testing "clean-tag converts special characters"
    (is (= "rock_and_roll" (tags/clean-tag "Rock&Roll")))
    (is (= "c_plus_plus" (tags/clean-tag "C++Programming")))
    (is (= "email_at_domain" (tags/clean-tag "email@domain")))))

(deftest clean-tag-whitespace-test
  (testing "clean-tag handles whitespace and punctuation"
    (is (= "action_adventure" (tags/clean-tag "Action Adventure")))
    (is (= "sci_fi" (tags/clean-tag "Sci-Fi")))
    (is (= "comedy_drama" (tags/clean-tag "Comedy/Drama")))))

(deftest clean-tag-multiple-underscores-test
  (testing "clean-tag collapses multiple underscores"
    (is (= "foo_bar" (tags/clean-tag "foo___bar")))
    (is (= "test_tag" (tags/clean-tag "test----tag")))))

(deftest clean-tag-trim-test
  (testing "clean-tag trims leading/trailing underscores"
    (is (= "clean" (tags/clean-tag "_clean_")))
    (is (= "tag" (tags/clean-tag "---tag---")))))

(deftest clean-tag-unicode-test
  (testing "clean-tag preserves unicode letters and numbers"
    (is (= "café" (tags/clean-tag "Café")))
    (is (= "日本語" (tags/clean-tag "日本語")))))

(deftest clean-tag-empty-result-test
  (testing "clean-tag returns original if result would be empty"
    (is (= "###" (tags/clean-tag "###")))
    (is (= "..." (tags/clean-tag "...")))))

;; Tag transformation file loading tests
(deftest load-tag-transforms-file-not-found-test
  (testing "load-tag-transforms throws when file doesn't exist"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                         #"file does not exist"
                         (tags/load-tag-transforms "nonexistent-file.csv")))))

(deftest load-tag-transforms-valid-file-test
  (testing "load-tag-transforms reads CSV file correctly"
    (let [temp-file (java.io.File/createTempFile "tag-rules" ".csv")]
      (try
        (spit temp-file "action,rename,action-packed\nhorror,drop\ncomedy,\n")
        (let [transforms (tags/load-tag-transforms (.getPath temp-file))]
          (is (= 3 (count transforms)))
          (is (= :rename (:type (first transforms))))
          (is (= :drop (:type (second transforms))))
          (is (= :no-op (:type (nth transforms 2)))))
        (finally
          (.delete temp-file))))))

;; Normalize tag tests
(deftest normalize-tag-rename-test
  (testing "normalize-tag! renames a tag"
    (let [catalog (->MockCatalog (atom {:tags [:action :comedy]}))]
      (tags/normalize-tag! catalog {:type :rename :tag "action" :new-tag "action-packed"})
      (is (= 1 (count (get-in @(:state catalog) [:rename-log]))))
      (is (= "action" (get-in @(:state catalog) [:rename-log 0 :tag])))
      (is (= "action-packed" (get-in @(:state catalog) [:rename-log 0 :new-tag]))))))

(deftest normalize-tag-drop-test
  (testing "normalize-tag! drops a tag"
    (let [catalog (->MockCatalog (atom {:tags [:action :comedy :inappropriate]}))]
      (tags/normalize-tag! catalog {:type :drop :tag "inappropriate"})
      (is (= 1 (count (get-in @(:state catalog) [:delete-log]))))
      (is (= "inappropriate" (get-in @(:state catalog) [:delete-log 0 :tag]))))))

(deftest normalize-tag-no-op-test
  (testing "normalize-tag! does nothing for no-op"
    (let [catalog (->MockCatalog (atom {:tags [:action :comedy]}))]
      (tags/normalize-tag! catalog {:type :no-op :tag "action"})
      ;; Should not modify the catalog
      (is (= [:action :comedy] (get-in @(:state catalog) [:tags]))))))

;; Full normalization workflow tests
(deftest normalize-cleans-tags-test
  (testing "normalize! cleans tag names"
    (let [catalog (->MockCatalog (atom {:tags [:Action-Adventure :Sci-Fi]}))]
      (tags/normalize! catalog {})
      ;; Should have renamed tags to cleaned versions
      (is (>= (count (get-in @(:state catalog) [:rename-log])) 1)))))

(deftest normalize-with-transforms-file-test
  (testing "normalize! applies tag transforms from file"
    (let [temp-file (java.io.File/createTempFile "tag-rules" ".csv")
          catalog (->MockCatalog (atom {:tags [:action :horror :comedy]}))]
      (try
        (spit temp-file "action,rename,action-packed\nhorror,drop\n")
        (tags/normalize! catalog {:tag-transforms-file (.getPath temp-file)})
        ;; Should have applied the transformations
        (is (seq (get-in @(:state catalog) [:rename-log])))
        (is (seq (get-in @(:state catalog) [:delete-log])))
        (finally
          (.delete temp-file))))))

(deftest normalize-without-transforms-file-test
  (testing "normalize! skips file transforms when not provided"
    (let [catalog (->MockCatalog (atom {:tags [:action :comedy]}))]
      (tags/normalize! catalog {})
      ;; Should only clean tags, not apply file-based transforms
      (is (or (empty? (get-in @(:state catalog) [:delete-log]))
              (nil? (get-in @(:state catalog) [:delete-log])))))))

(deftest normalize-tag-rename-missing-fields-test
  (testing "normalize-tag! handles missing fields gracefully"
    (let [catalog (->MockCatalog (atom {:tags [:action]}))]
      ;; Missing new-tag should log error but not throw
      (tags/normalize-tag! catalog {:type :rename :tag "action"})
      ;; Should not have performed rename
      (is (= [:action] (catalog/get-tags catalog))))))

(deftest normalize-tag-drop-missing-tag-test
  (testing "normalize-tag! handles missing tag field gracefully"
    (let [catalog (->MockCatalog (atom {:tags [:action :comedy]}))]
      ;; Missing tag should log error but not throw
      (tags/normalize-tag! catalog {:type :drop})
      ;; Should not have performed deletion
      (is (= 2 (count (catalog/get-tags catalog)))))))
