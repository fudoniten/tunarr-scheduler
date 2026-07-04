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
      library_id TEXT REFERENCES library(id),
      parent_id TEXT REFERENCES media(id),
      season_number INTEGER,
      episode_number INTEGER
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
    )"])
  (jdbc/execute! db ["
    CREATE TABLE IF NOT EXISTS media_context (
      media_id TEXT PRIMARY KEY REFERENCES media(id),
      text TEXT,
      links TEXT NOT NULL DEFAULT '[]',
      summary TEXT,
      source TEXT,
      operator_edited BOOLEAN NOT NULL DEFAULT FALSE,
      updated_at TIMESTAMP DEFAULT NOW()
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
      (is (some? media))
      (is (= "Test Movie" (::media/name media))))))

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

(deftest search-media-by-library-id-test
  (testing "filters by case-insensitive substring match against name"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media-batch! *test-catalog* [sample-movie sample-series])

    (let [library-id (catalog/get-library-id *test-catalog* :test-library)]
      (is (= ["movie-1"]
             (map :media/id (catalog/search-media-by-library-id *test-catalog* library-id {:q "movie"}))))
      (is (= ["series-1"]
             (map :media/id (catalog/search-media-by-library-id *test-catalog* library-id {:q "SERIES"}))))
      (is (= #{"movie-1" "series-1"}
             (set (map :media/id (catalog/search-media-by-library-id *test-catalog* library-id {:q "test"})))))
      (is (empty? (catalog/search-media-by-library-id *test-catalog* library-id {:q "nonexistent-text"})))))

  (testing "filters by overview match too"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media-batch! *test-catalog* [sample-movie sample-series])

    (let [library-id (catalog/get-library-id *test-catalog* :test-library)]
      (is (= ["movie-1"]
             (map :media/id (catalog/search-media-by-library-id *test-catalog* library-id {:q "A test movie"})))))))

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
      (is (some #(= :process/tagging (::media/process-name %)) timestamps)))))

;; Category value tests
(deftest add-media-category-value-test
  (testing "add single category value to media"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-movie)

    (catalog/add-media-category-value! *test-catalog* "movie-1" :mood :exciting nil)

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

    (let [media (catalog/get-media-by-id *test-catalog* "movie-1")]
      (is (keyword? (::media/type media))))))

(deftest tag-array-transformation-test
  (testing "tags are transformed from arrays to vectors of keywords"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-movie)

    (let [media (first (catalog/get-media *test-catalog*))]
      (is (vector? (:tags media)))
      (is (every? keyword? (:tags media))))))

;; --- Episode-level tagging tests ---

(def sample-episode
  {::media/id "episode-1"
   ::media/name "The Pilot"
   ::media/overview "The first episode"
   ::media/community-rating 88.0
   ::media/critic-rating 85.0
   ::media/rating "TV-MA"
   ::media/type :episode
   ::media/media-type :episode
   ::media/production-year 2022
   ::media/subtitles? false
   ::media/premiere (LocalDate/of 2022 5 10)
   ::media/kid-friendly? true
   ::media/library-id "lib-1"
   ::media/parent-id "series-1"
   ::media/season-number 1
   ::media/episode-number 1
   ::media/tags [:pilot]
   ::media/genres [:comedy]
   ::media/channel-names []
   ::media/taglines []})

(def sample-episode-2
  (assoc sample-episode
         ::media/id "episode-2"
         ::media/name "The Contest"
         ::media/episode-number 2
         ::media/tags []))

(deftest add-and-get-episodes-test
  (testing "add episodes linked to a series and retrieve them"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    ;; Must add series first (parent FK)
    (catalog/add-media! *test-catalog* sample-series)
    (catalog/add-media! *test-catalog* sample-episode)
    (catalog/add-media! *test-catalog* sample-episode-2)

    (let [episodes (catalog/get-episodes-by-series *test-catalog* "series-1")]
      (is (= 2 (count episodes)))
      ;; Should be ordered by season/episode number
      (is (= "episode-1" (::media/id (first episodes))))
      (is (= "episode-2" (::media/id (second episodes)))))))

(deftest get-single-episode-test
  (testing "retrieve a specific episode by series, season, and episode number"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-series)
    (catalog/add-media! *test-catalog* sample-episode)
    (catalog/add-media! *test-catalog* sample-episode-2)

    (let [ep (catalog/get-episode *test-catalog* "series-1" 1 2)]
      (is (some? ep))
      (is (= "episode-2" (::media/id ep)))
      (is (= "The Contest" (::media/name ep))))))

(deftest get-episode-not-found-test
  (testing "returns nil when episode doesn't exist"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-series)

    (is (nil? (catalog/get-episode *test-catalog* "series-1" 99 99)))))

(deftest get-media-by-library-excludes-episodes-test
  (testing "get-media-by-library returns only movies and series, not episodes"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media-batch! *test-catalog* [sample-movie sample-series])
    (catalog/add-media! *test-catalog* sample-episode)

    (let [media (catalog/get-media-by-library *test-catalog* :test-library)]
      (is (= 2 (count media)))
      (is (= #{"movie-1" "series-1"}
             (set (map ::media/id media)))))))

(deftest effective-tags-for-episode-test
  (testing "effective tags for an episode are the union of series tags and episode tags"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-series)
    (catalog/add-media! *test-catalog* sample-episode)

    ;; series-1 has tags [:comedy :family], episode-1 has tags [:pilot]
    (let [effective (set (catalog/get-effective-tags *test-catalog* "episode-1"))]
      (is (contains? effective :pilot) "Episode's own tag should be present")
      (is (contains? effective :comedy) "Inherited series tag should be present")
      (is (contains? effective :family) "Inherited series tag should be present"))))

(deftest effective-tags-for-movie-test
  (testing "effective tags for a movie are just its own tags"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-movie)

    (let [effective (set (catalog/get-effective-tags *test-catalog* "movie-1"))]
      (is (contains? effective :action))
      (is (contains? effective :adventure)))))

(deftest effective-tags-episode-without-own-tags-test
  (testing "episode with no own tags inherits all series tags"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-series)
    (catalog/add-media! *test-catalog* sample-episode-2) ; has no tags

    (let [effective (set (catalog/get-effective-tags *test-catalog* "episode-2"))]
      (is (contains? effective :comedy))
      (is (contains? effective :family)))))

(deftest batch-insert-episodes-with-series-test
  (testing "batch insert handles series before episodes for FK ordering"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    ;; Insert episode before series in the batch - should work because batch sorts
    (catalog/add-media-batch! *test-catalog* [sample-episode sample-series sample-episode-2])

    (let [episodes (catalog/get-episodes-by-series *test-catalog* "series-1")]
      (is (= 2 (count episodes))))))

;; --- Upsert behavior tests (Pseudovision re-sync scenario) ---

(deftest upsert-updates-scalar-fields-test
  (testing "add-media! on an existing id refreshes scalar fields"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-movie)

    (catalog/add-media! *test-catalog*
                        (assoc sample-movie
                               ::media/name "Renamed Movie"
                               ::media/overview "Updated overview"
                               ::media/community-rating 99.0))

    (let [m (catalog/get-media-by-id *test-catalog* "movie-1")]
      (is (= "Renamed Movie" (::media/name m)))
      (is (= "Updated overview" (::media/overview m)))
      (is (= 99.0 (::media/community-rating m))))))

(deftest upsert-preserves-curation-tags-test
  (testing "upserting an item does not drop tags added later by curation"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-movie)

    ;; Simulate curation adding an LLM-generated tag.
    (catalog/add-media-tags! *test-catalog* "movie-1" [:llm-inferred])

    ;; Re-sync from upstream — incoming payload has the original tags only.
    (catalog/add-media! *test-catalog* sample-movie)

    (let [tags (set (catalog/get-media-tags *test-catalog* "movie-1"))]
      (is (contains? tags :llm-inferred) "curation tag must be preserved")
      (is (contains? tags :action) "upstream tag still present")
      (is (contains? tags :adventure) "upstream tag still present"))))

(deftest upsert-merges-new-upstream-tags-test
  (testing "new tags from the upstream payload are merged into existing set"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-movie)
    (catalog/add-media-tags! *test-catalog* "movie-1" [:llm-inferred])

    (catalog/add-media! *test-catalog*
                        (update sample-movie ::media/tags conj :new-upstream-tag))

    (let [tags (set (catalog/get-media-tags *test-catalog* "movie-1"))]
      (is (contains? tags :new-upstream-tag))
      (is (contains? tags :llm-inferred))
      (is (contains? tags :action)))))

(deftest upsert-preserves-categories-test
  (testing "upsert does not disturb media_categorization entries"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-movie)
    (catalog/add-media-category-values! *test-catalog* "movie-1" :mood [:exciting :intense])

    (catalog/add-media! *test-catalog*
                        (assoc sample-movie ::media/name "Renamed"))

    (let [vals (set (catalog/get-media-category-values *test-catalog* "movie-1" :mood))]
      (is (= #{:exciting :intense} vals)))))

(deftest upsert-batch-refreshes-existing-and-inserts-new-test
  (testing "add-media-batch! upserts existing rows and inserts new ones"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-movie)
    (catalog/add-media-tags! *test-catalog* "movie-1" [:curated])

    (catalog/add-media-batch! *test-catalog*
                              [(assoc sample-movie ::media/name "Movie v2")
                               sample-series])

    (let [movie  (catalog/get-media-by-id *test-catalog* "movie-1")
          series (catalog/get-media-by-id *test-catalog* "series-1")
          tags   (set (catalog/get-media-tags *test-catalog* "movie-1"))]
      (is (= "Movie v2" (::media/name movie)))
      (is (= "Test Series" (::media/name series)))
      (is (contains? tags :curated) "curation tag survives batch upsert"))))

;; ---------------------------------------------------------------------------
;; Per-media grounding context (media_context)
;; ---------------------------------------------------------------------------

(deftest media-context-round-trip-test
  (testing "set/get context round-trips, decoding the JSON links column"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-movie)
    (is (nil? (catalog/get-media-context *test-catalog* "movie-1"))
        "no context stored yet")

    (catalog/set-media-context!
     *test-catalog* "movie-1"
     {:text            "Operator note"
      :links           ["https://en.wikipedia.org/wiki/Juice_(1992_film)"]
      :summary         "Juice (1992) is a crime drama set in Harlem."
      :source          "provided-summary"
      :operator-edited true})

    (let [ctx (catalog/get-media-context *test-catalog* "movie-1")]
      (is (= "Operator note" (:text ctx)))
      (is (= ["https://en.wikipedia.org/wiki/Juice_(1992_film)"] (:links ctx)))
      (is (= "Juice (1992) is a crime drama set in Harlem." (:summary ctx)))
      (is (= "provided-summary" (:source ctx)))
      (is (true? (:operator-edited ctx)))
      (is (some? (:updated-at ctx)) "updated-at is stamped"))))

(deftest media-context-upsert-replaces-test
  (testing "set-media-context! upserts (replaces) an existing row"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-movie)
    (catalog/set-media-context! *test-catalog* "movie-1"
                                {:summary "first" :links ["a"]})
    (catalog/set-media-context! *test-catalog* "movie-1"
                                {:summary "second" :links [] :operator-edited true})
    (let [ctx (catalog/get-media-context *test-catalog* "movie-1")]
      (is (= "second" (:summary ctx)))
      (is (= [] (:links ctx)))
      (is (true? (:operator-edited ctx))))))

(deftest media-context-defaults-test
  (testing "a context stored with only a summary reads back sane defaults"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-movie)
    (catalog/set-media-context! *test-catalog* "movie-1" {:summary "only summary"})
    (let [ctx (catalog/get-media-context *test-catalog* "movie-1")]
      (is (= "only summary" (:summary ctx)))
      (is (= [] (:links ctx)) "absent links default to an empty vector")
      (is (false? (:operator-edited ctx)) "operator-edited defaults to false"))))

(deftest media-context-delete-test
  (testing "delete-media-context! removes the stored context"
    (catalog/update-libraries! *test-catalog* {:test-library "lib-1"})
    (catalog/add-media! *test-catalog* sample-movie)
    (catalog/set-media-context! *test-catalog* "movie-1" {:summary "x"})
    (is (some? (catalog/get-media-context *test-catalog* "movie-1")))
    (catalog/delete-media-context! *test-catalog* "movie-1")
    (is (nil? (catalog/get-media-context *test-catalog* "movie-1")))))
