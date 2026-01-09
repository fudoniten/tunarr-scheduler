(ns tunarr.scheduler.media.sql-catalog-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.media.sql-catalog :as sql-catalog]
            [tunarr.scheduler.sql.executor :as executor]
            [next.jdbc :as jdbc])
  (:import java.time.LocalDate))

;; Test database setup and teardown
(def ^:dynamic *test-db* nil)
(def ^:dynamic *test-catalog* nil)

(defn create-test-db []
  "Create an in-memory H2 database for testing"
  (jdbc/get-datasource {:dbtype "h2:mem"
                        :dbname "test"
                        :DB_CLOSE_DELAY "-1"}))

(defn setup-schema [db]
  "Set up the test database schema"
  (jdbc/execute! db ["
    CREATE TABLE IF NOT EXISTS library (
      id TEXT PRIMARY KEY,
      name TEXT UNIQUE NOT NULL
    )"])
  (jdbc/execute! db ["
    CREATE TABLE IF NOT EXISTS channel (
      name TEXT PRIMARY KEY,
      full_name TEXT,
      id TEXT,
      description TEXT
    )"])
  (jdbc/execute! db ["
    CREATE TABLE IF NOT EXISTS media (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      overview TEXT,
      community_rating DOUBLE,
      critic_rating DOUBLE,
      rating TEXT,
      media_type TEXT NOT NULL,
      production_year INTEGER,
      subtitles BOOLEAN,
      premiere DATE,
      kid_friendly BOOLEAN,
      library_id TEXT REFERENCES library(id)
    )"])
  (jdbc/execute! db ["
    CREATE TABLE IF NOT EXISTS tag (
      name TEXT PRIMARY KEY
    )"])
  (jdbc/execute! db ["
    CREATE TABLE IF NOT EXISTS genre (
      name TEXT PRIMARY KEY
    )"])
  (jdbc/execute! db ["
    CREATE TABLE IF NOT EXISTS media_tags (
      media_id TEXT REFERENCES media(id),
      tag TEXT REFERENCES tag(name),
      PRIMARY KEY (media_id, tag)
    )"])
  (jdbc/execute! db ["
    CREATE TABLE IF NOT EXISTS media_genres (
      media_id TEXT REFERENCES media(id),
      genre TEXT REFERENCES genre(name),
      PRIMARY KEY (media_id, genre)
    )"])
  (jdbc/execute! db ["
    CREATE TABLE IF NOT EXISTS media_channels (
      media_id TEXT REFERENCES media(id),
      channel TEXT REFERENCES channel(name),
      PRIMARY KEY (media_id, channel)
    )"])
  (jdbc/execute! db ["
    CREATE TABLE IF NOT EXISTS media_taglines (
      media_id TEXT REFERENCES media(id),
      tagline TEXT,
      PRIMARY KEY (media_id, tagline)
    )"])
  (jdbc/execute! db ["
    CREATE TABLE IF NOT EXISTS media_process_timestamp (
      media_id TEXT REFERENCES media(id),
      process TEXT,
      last_run_at TIMESTAMP,
      PRIMARY KEY (media_id, process)
    )"])
  (jdbc/execute! db ["
    CREATE TABLE IF NOT EXISTS media_categorization (
      media_id TEXT REFERENCES media(id),
      category TEXT,
      category_value TEXT,
      PRIMARY KEY (media_id, category, category_value)
    )"]))

(defn test-fixture [f]
  (let [db (create-test-db)
        exec (executor/create-executor db :worker-count 2 :queue-size 10)]
    (setup-schema db)
    (binding [*test-db* db
              *test-catalog* (sql-catalog/->SqlCatalog exec)]
      (try
        (f)
        (finally
          (catalog/close-catalog! *test-catalog*))))))

(use-fixtures :each test-fixture)

;; Sample test data
(def sample-movie
  {::media/id "movie-1"
   ::media/name "Test Movie"
   ::media/overview "A test movie"
   ::media/community-rating 85.5
   ::media/critic-rating 90.0
   ::media/rating "PG-13"
   ::media/type :movie
   ::media/media-type :movie
   ::media/production-year 2023
   ::media/subtitles? true
   ::media/premiere (LocalDate/of 2023 1 15)
   ::media/kid-friendly? false
   ::media/library-id "lib-1"
   ::media/tags [:action :adventure]
   ::media/genres [:action :thriller]
   ::media/channel-names [:prime-time :action-channel]
   ::media/taglines ["An epic adventure" "The journey begins"]})

(def sample-series
  {::media/id "series-1"
   ::media/name "Test Series"
   ::media/overview "A test series"
   ::media/community-rating 92.0
   ::media/critic-rating 88.5
   ::media/rating "TV-MA"
   ::media/type :series
   ::media/media-type :series
   ::media/production-year 2022
   ::media/subtitles? false
   ::media/premiere (LocalDate/of 2022 5 10)
   ::media/kid-friendly? true
   ::media/library-id "lib-1"
   ::media/tags [:comedy :family]
   ::media/genres [:comedy]
   ::media/channel-names [:family-channel]
   ::media/taglines ["Laugh out loud"]})

;; Tests for add-media! and get-media
(deftest add-and-get-media-test
  (testing "add single media and retrieve it"
    ;; First add a library
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})

    (catalog/add-media! *test-catalog* sample-movie)
    (let [media (catalog/get-media *test-catalog*)]
      (is (= 1 (count media)))
      (is (= "movie-1" (get-in media [0 :media/id])))
      (is (= "Test Movie" (get-in media [0 :media/name]))))))

(deftest add-media-batch-test
  (testing "add multiple media items in a batch"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})

    (catalog/add-media-batch! *test-catalog* [sample-movie sample-series])
    (let [media (catalog/get-media *test-catalog*)]
      (is (= 2 (count media)))
      (is (= #{"movie-1" "series-1"}
             (set (map :media/id media)))))))

(deftest get-media-by-id-test
  (testing "retrieve specific media by id"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-movie)

    (let [media (catalog/get-media-by-id *test-catalog* "movie-1")]
      (is (seq media))
      (is (= "Test Movie" (:media/name (first media)))))))

(deftest get-media-by-library-test
  (testing "retrieve media by library name"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media-batch! *test-catalog* [sample-movie sample-series])

    (let [media (catalog/get-media-by-library *test-catalog* :test-library)]
      (is (= 2 (count media))))))

(deftest get-media-by-library-not-found-test
  (testing "throws exception when library not found"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"library not found"
                          (catalog/get-media-by-library *test-catalog* :nonexistent)))))

;; Tag management tests
(deftest add-media-tags-test
  (testing "add tags to existing media"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-movie)

    (catalog/add-media-tags! *test-catalog* "movie-1" [:new-tag :another-tag])
    (let [tags (catalog/get-media-tags *test-catalog* "movie-1")]
      (is (>= (count tags) 2))
      (is (contains? (set tags) :new-tag))
      (is (contains? (set tags) :another-tag)))))

(deftest set-media-tags-test
  (testing "set tags replaces existing tags"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-movie)

    ;; Original tags are :action and :adventure
    (catalog/set-media-tags! *test-catalog* "movie-1" [:horror :thriller])
    (let [tags (catalog/get-media-tags *test-catalog* "movie-1")]
      (is (= 2 (count tags)))
      (is (contains? (set tags) :horror))
      (is (contains? (set tags) :thriller)))))

(deftest get-tags-test
  (testing "get all unique tags from catalog"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media-batch! *test-catalog* [sample-movie sample-series])

    (let [tags (catalog/get-tags *test-catalog*)]
      (is (>= (count tags) 3))
      (is (contains? (set tags) :action))
      (is (contains? (set tags) :comedy)))))

(deftest get-tag-samples-test
  (testing "get tag samples with usage counts"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media-batch! *test-catalog* [sample-movie sample-series])

    (let [samples (catalog/get-tag-samples *test-catalog*)]
      (is (seq samples))
      (is (every? #(contains? % :tag) samples))
      (is (every? #(contains? % :usage_count) samples))
      (is (every? #(contains? % :example_titles) samples)))))

(deftest delete-tag-test
  (testing "delete a tag from catalog"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-movie)

    (let [tags-before (catalog/get-tags *test-catalog*)]
      (is (contains? (set tags-before) :action))

      (catalog/delete-tag! *test-catalog* "action")

      (let [tags-after (catalog/get-tags *test-catalog*)]
        (is (not (contains? (set tags-after) :action)))))))

(deftest rename-tag-test
  (testing "rename a tag in catalog"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-movie)

    (catalog/rename-tag! *test-catalog* "action" "action-packed")

    (let [tags (catalog/get-tags *test-catalog*)]
      (is (not (contains? (set tags) :action)))
      (is (contains? (set tags) :action-packed)))))

(deftest rename-tag-merge-test
  (testing "rename tag merges when target exists"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-movie)

    ;; Add another tag
    (catalog/add-media-tags! *test-catalog* "movie-1" [:adventure])

    ;; Rename action to adventure (both exist, should merge)
    (catalog/rename-tag! *test-catalog* "action" "adventure")

    (let [tags (catalog/get-media-tags *test-catalog* "movie-1")]
      (is (contains? (set tags) :adventure))
      (is (not (contains? (set tags) :action))))))

;; Channel and genre tests
(deftest update-channels-test
  (testing "update channel descriptions"
    (let [channels {:action-channel {::media/channel-fullname "Action Channel"
                                    ::media/channel-id "ch-1"
                                    ::media/channel-description "Action movies"}
                   :comedy-channel {::media/channel-fullname "Comedy Channel"
                                   ::media/channel-id "ch-2"
                                   ::media/channel-description "Comedy shows"}}]
      (catalog/update-channels! *test-catalog* channels)
      ;; Verify channels were added (no direct getter, but add-media-channels should work)
      (is true))))

(deftest add-media-channels-test
  (testing "add channels to media"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-movie)

    (let [channels {:drama-channel {::media/channel-fullname "Drama Channel"
                                   ::media/channel-id "ch-3"
                                   ::media/channel-description "Drama content"}}]
      (catalog/update-channels! *test-catalog* channels)
      (catalog/add-media-channels! *test-catalog* "movie-1" [:drama-channel])
      ;; If this doesn't throw, channels were added successfully
      (is true))))

(deftest add-media-genres-test
  (testing "add genres to media"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-movie)

    (catalog/add-media-genres! *test-catalog* "movie-1" [:horror :mystery])
    ;; If this doesn't throw, genres were added successfully
    (is true)))

(deftest get-media-by-channel-test
  (testing "retrieve media by channel"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (let [channels {:action-channel {::media/channel-fullname "Action Channel"
                                    ::media/channel-id "ch-1"
                                    ::media/channel-description "Action"}}]
      (catalog/update-channels! *test-catalog* channels)
      (catalog/add-media! *test-catalog* sample-movie))

    (let [media (catalog/get-media-by-channel *test-catalog* :action-channel)]
      (is (seq media)))))

(deftest get-media-by-tag-test
  (testing "retrieve media by tag"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-movie)

    (let [media (catalog/get-media-by-tag *test-catalog* :action)]
      (is (= 1 (count media)))
      (is (= "movie-1" (:media/id (first media)))))))

(deftest get-media-by-genre-test
  (testing "retrieve media by genre"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-movie)

    (let [media (catalog/get-media-by-genre *test-catalog* :thriller)]
      (is (seq media)))))

;; Tagline tests
(deftest add-media-taglines-test
  (testing "add taglines to media"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-movie)

    (catalog/add-media-taglines! *test-catalog* "movie-1" ["New tagline" "Another one"])
    ;; If this doesn't throw, taglines were added successfully
    (is true)))

;; Process timestamp tests
(deftest update-process-timestamp-test
  (testing "update process timestamp for media"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-movie)

    (catalog/update-process-timestamp! *test-catalog* "movie-1" "tagging")

    (let [timestamps (catalog/get-media-process-timestamps *test-catalog* sample-movie)]
      (is (seq timestamps))
      (is (some #(= :process/tagging (:media/process-name %)) timestamps)))))

;; Category value tests
(deftest add-media-category-value-test
  (testing "add single category value to media"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-movie)

    (catalog/add-media-category-value! *test-catalog* "movie-1" :mood :exciting)

    (let [values (catalog/get-media-category-values *test-catalog* "movie-1" :mood)]
      (is (= 1 (count values)))
      (is (= :exciting (first values))))))

(deftest add-media-category-values-test
  (testing "add multiple category values to media"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-movie)

    (catalog/add-media-category-values! *test-catalog* "movie-1" :mood [:exciting :intense :dramatic])

    (let [values (catalog/get-media-category-values *test-catalog* "movie-1" :mood)]
      (is (= 3 (count values)))
      (is (contains? (set values) :exciting))
      (is (contains? (set values) :intense))
      (is (contains? (set values) :dramatic)))))

(deftest set-media-category-values-test
  (testing "set category values replaces existing values"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-movie)

    (catalog/add-media-category-values! *test-catalog* "movie-1" :mood [:exciting :intense])
    (catalog/set-media-category-values! *test-catalog* "movie-1" :mood [:calm :peaceful])

    (let [values (catalog/get-media-category-values *test-catalog* "movie-1" :mood)]
      (is (= 2 (count values)))
      (is (contains? (set values) :calm))
      (is (contains? (set values) :peaceful))
      (is (not (contains? (set values) :exciting)))
      (is (not (contains? (set values) :intense))))))

(deftest get-media-categories-test
  (testing "get all categories for media"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-movie)

    (catalog/add-media-category-values! *test-catalog* "movie-1" :mood [:exciting])
    (catalog/add-media-category-values! *test-catalog* "movie-1" :tone [:dark])

    (let [categories (catalog/get-media-categories *test-catalog* "movie-1")]
      (is (= 2 (count categories)))
      (is (contains? categories :mood))
      (is (contains? categories :tone))
      (is (contains? (set (:mood categories)) :exciting))
      (is (contains? (set (:tone categories)) :dark)))))

(deftest delete-media-category-value-test
  (testing "delete single category value from media"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-movie)

    (catalog/add-media-category-values! *test-catalog* "movie-1" :mood [:exciting :intense])
    (catalog/delete-media-category-value! *test-catalog* "movie-1" :mood :exciting)

    (let [values (catalog/get-media-category-values *test-catalog* "movie-1" :mood)]
      (is (= 1 (count values)))
      (is (= :intense (first values))))))

(deftest delete-media-category-values-test
  (testing "delete all category values for a category from media"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-movie)

    (catalog/add-media-category-values! *test-catalog* "movie-1" :mood [:exciting :intense])
    (catalog/delete-media-category-values! *test-catalog* "movie-1" :mood)

    (let [values (catalog/get-media-category-values *test-catalog* "movie-1" :mood)]
      (is (empty? values)))))

;; Library management tests
(deftest update-libraries-test
  (testing "update library information"
    (catalog/update-libraries! *test-catalog* {:movies "lib-movies"
                                              :shows "lib-shows"})
    ;; If this doesn't throw, libraries were updated successfully
    (is true)))

;; Test field transformations
(deftest media-field-transformations-test
  (testing "media type keyword transformation"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-movie)

    (let [media (first (catalog/get-media-by-id *test-catalog* "movie-1"))]
      (is (keyword? (:media/media_type media))))))

(deftest tag-array-transformation-test
  (testing "tags are transformed from arrays to vectors of keywords"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-movie)

    (let [media (first (catalog/get-media *test-catalog*))]
      (is (vector? (:tags media)))
      (is (every? keyword? (:tags media))))))
