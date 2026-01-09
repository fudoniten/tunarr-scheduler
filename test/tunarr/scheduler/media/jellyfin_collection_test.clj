(ns tunarr.scheduler.media.jellyfin-collection-test
  (:require [clojure.test :refer [deftest is testing]]
            [tunarr.scheduler.media.jellyfin-collection :as jellyfin]
            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.media.collection :as collection]
            [clj-http.client :as http])
  (:import [java.time LocalDate]))

;; Helper functions for testing
(defn mock-jellyfin-response [items]
  {:Items items})

(defn sample-jellyfin-movie []
  {:Id "movie-123"
   :Name "Test Movie"
   :Overview "A test movie overview"
   :Type "Movie"
   :ProductionYear 2023
   :HasSubtitles true
   :PremiereDate "2023-01-15T00:00:00Z"
   :OfficialRating "PG-13"
   :CommunityRating 8.5
   :CriticRating 85
   :Tags ["Action" "Adventure"]
   :Genres ["Action" "Thriller"]
   :Taglines ["An epic journey"]})

(defn sample-jellyfin-series []
  {:Id "series-456"
   :Name "Test Series"
   :Overview "A test series overview"
   :Type "Series"
   :ProductionYear 2022
   :HasSubtitles false
   :PremiereDate "2022-05-10T00:00:00Z"
   :OfficialRating "TV-MA"
   :CommunityRating 9.2
   :CriticRating 88
   :Tags ["Comedy" "Family"]
   :Genres ["Comedy"]
   :Taglines []})

;; URL building tests
(deftest build-url-basic-test
  (testing "build-url constructs basic URL"
    (let [url (jellyfin/build-url "http://localhost:8096" :path "/Items")]
      (is (= "http://localhost:8096/Items" url)))))

(deftest build-url-with-params-test
  (testing "build-url includes query parameters"
    (let [url (jellyfin/build-url "http://localhost:8096"
                                 :path "/Items"
                                 :params {:Recursive true
                                         :SortBy "SortName"})]
      (is (.contains url "Recursive=true"))
      (is (.contains url "SortBy=SortName")))))

;; Rating normalization tests
(deftest normalize-rating-1-10-test
  (testing "normalize-rating keeps ratings 1-10 unchanged"
    (is (= 8.5 (jellyfin/normalize-rating 8.5)))
    (is (= 9 (jellyfin/normalize-rating 9)))
    (is (= 10 (jellyfin/normalize-rating 10)))))

(deftest normalize-rating-1-100-test
  (testing "normalize-rating converts 1-100 ratings to 1-10"
    (is (= 8.5 (jellyfin/normalize-rating 85)))
    (is (= 9.2 (jellyfin/normalize-rating 92)))
    (is (= 10.0 (jellyfin/normalize-rating 100)))))

(deftest normalize-rating-edge-cases-test
  (testing "normalize-rating handles edge cases"
    (is (nil? (jellyfin/normalize-rating nil)))
    (is (= 0 (jellyfin/normalize-rating 0)))
    (is (= 1 (jellyfin/normalize-rating 1)))))

;; Item parsing tests
(deftest parse-jellyfin-item-movie-test
  (testing "parse-jellyfin-item transforms movie data correctly"
    (let [item (sample-jellyfin-movie)
          parsed (jellyfin/parse-jellyfin-item item)]
      (is (= "Test Movie" (::media/name parsed)))
      (is (= "A test movie overview" (::media/overview parsed)))
      (is (= "movie-123" (::media/id parsed)))
      (is (= :movie (::media/type parsed)))
      (is (= 2023 (::media/production-year parsed)))
      (is (= false (::media/subtitles? parsed)))
      (is (= "PG-13" (::media/rating parsed)))
      (is (= 8.5 (::media/community-rating parsed)))
      (is (= 8.5 (::media/critic-rating parsed))) ; 85 normalized to 8.5
      (is (= [:action :adventure] (::media/tags parsed)))
      (is (= [:action :thriller] (::media/genres parsed)))
      (is (= ["An epic journey"] (::media/taglines parsed)))
      (is (instance? LocalDate (::media/premiere parsed))))))

(deftest parse-jellyfin-item-series-test
  (testing "parse-jellyfin-item transforms series data correctly"
    (let [item (sample-jellyfin-series)
          parsed (jellyfin/parse-jellyfin-item item)]
      (is (= "Test Series" (::media/name parsed)))
      (is (= :series (::media/type parsed)))
      (is (= 2022 (::media/production-year parsed)))
      (is (= [:comedy :family] (::media/tags parsed)))
      (is (= [] (::media/taglines parsed))))))

(deftest parse-jellyfin-item-missing-fields-test
  (testing "parse-jellyfin-item handles missing optional fields"
    (let [minimal-item {:Id "test-id"
                       :Name "Test"
                       :Type "Movie"
                       :Overview "Test overview"}
          parsed (jellyfin/parse-jellyfin-item minimal-item)]
      (is (= "Test" (::media/name parsed)))
      (is (= "test-id" (::media/id parsed)))
      (is (= :movie (::media/type parsed)))
      (is (= false (::media/subtitles? parsed)))
      (is (= [] (::media/tags parsed)))
      (is (= [] (::media/taglines parsed)))
      (is (instance? LocalDate (::media/premiere parsed)))))) ; defaults to now

(deftest parse-jellyfin-item-kebab-case-conversion-test
  (testing "parse-jellyfin-item converts tags and genres to kebab-case keywords"
    (let [item {:Id "test"
               :Name "Test"
               :Type "Movie"
               :Tags ["Science Fiction" "Action/Adventure"]
               :Genres ["Sci-Fi" "Action-Adventure"]}
          parsed (jellyfin/parse-jellyfin-item item)]
      (is (every? keyword? (::media/tags parsed)))
      (is (every? keyword? (::media/genres parsed))))))

;; Collection implementation tests
(deftest jellyfin-fetch-library-items-test
  (testing "jellyfin:fetch-library-items retrieves and transforms items"
    (with-redefs [http/get (fn [_ _]
                            {:status 200
                             :body (cheshire.core/generate-string
                                   (mock-jellyfin-response
                                    [(sample-jellyfin-movie)
                                     (sample-jellyfin-series)]))})]
      (let [config {:base-url "http://localhost:8096"
                   :api-key "test-key"
                   :libraries {:movies "lib-123"}}
            items (jellyfin/jellyfin:fetch-library-items config :movies)]
        (is (= 2 (count items)))
        (is (= "Test Movie" (::media/name (first items))))
        (is (= "Test Series" (::media/name (second items))))
        ;; Verify library-id was added
        (is (= "lib-123" (::media/library-id (first items))))))))

(deftest jellyfin-fetch-library-items-not-found-test
  (testing "jellyfin:fetch-library-items throws when library not found"
    (let [config {:base-url "http://localhost:8096"
                 :api-key "test-key"
                 :libraries {:movies "lib-123"}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                           #"media library not found"
                           (jellyfin/jellyfin:fetch-library-items config :shows))))))

(deftest jellyfin-collection-get-library-items-test
  (testing "JellyfinMediaCollection get-library-items works end-to-end"
    (with-redefs [http/get (fn [_ _]
                            {:status 200
                             :body (cheshire.core/generate-string
                                   (mock-jellyfin-response
                                    [(sample-jellyfin-movie)]))})]
      (let [config {:base-url "http://localhost:8096"
                   :api-key "test-key"
                   :libraries {:movies "lib-123"}
                   :verbose false}
            coll (jellyfin/->JellyfinMediaCollection config)
            items (collection/get-library-items coll :movies)]
        (is (= 1 (count items)))
        (is (= "Test Movie" (::media/name (first items))))))))

(deftest jellyfin-request-includes-auth-header-test
  (testing "jellyfin-request includes X-Emby-Token header"
    (let [received-headers (atom nil)]
      (with-redefs [http/get (fn [_ opts]
                              (reset! received-headers (:headers opts))
                              {:status 200
                               :body (cheshire.core/generate-string {})})]
        (jellyfin/jellyfin-request {:api-key "secret-key"} "http://test.local/api")
        (is (= "secret-key" (get @received-headers "X-Emby-Token")))))))

(deftest jellyfin-request-constructs-correct-url-test
  (testing "jellyfin:fetch-library-items constructs correct Jellyfin API URL"
    (let [called-url (atom nil)]
      (with-redefs [http/get (fn [url _]
                              (reset! called-url url)
                              {:status 200
                               :body (cheshire.core/generate-string
                                     (mock-jellyfin-response []))})]
        (let [config {:base-url "http://localhost:8096"
                     :api-key "test-key"
                     :libraries {:movies "lib-123"}}]
          (jellyfin/jellyfin:fetch-library-items config :movies)
          (is (.contains @called-url "/Items"))
          (is (.contains @called-url "Recursive=true"))
          (is (.contains @called-url "ParentId=lib-123"))
          (is (.contains @called-url "IncludeItemTypes=Movie%2CSeries")))))))

(deftest initialize-collection-test
  (testing "initialize-collection! creates JellyfinMediaCollection"
    (let [config {:type :jellyfin
                 :base-url "http://localhost:8096"
                 :api-key "test-key"
                 :libraries {:movies "lib-1"}
                 :verbose false}
          coll (collection/initialize-collection! config)]
      (is (instance? tunarr.scheduler.media.jellyfin_collection.JellyfinMediaCollection coll))
      (is (= config (:config coll))))))

(deftest initialize-collection-invalid-config-test
  (testing "initialize-collection! throws on invalid config"
    (let [invalid-config {:type :jellyfin
                         :base-url "http://localhost:8096"
                         ;; missing required keys
                         }]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                           #"invalid collection spec"
                           (collection/initialize-collection! invalid-config))))))

(deftest jellyfin-collection-implements-closeable-test
  (testing "JellyfinMediaCollection implements close!"
    (let [config {:base-url "http://localhost:8096"
                 :api-key "test-key"
                 :libraries {}
                 :verbose false}
          coll (jellyfin/->JellyfinMediaCollection config)]
      ;; Should not throw
      (collection/close! coll))))

(deftest parse-jellyfin-item-handles-nil-premiere-date-test
  (testing "parse-jellyfin-item defaults to current date when PremiereDate is nil"
    (let [item {:Id "test"
               :Name "Test"
               :Type "Movie"
               :PremiereDate nil}
          parsed (jellyfin/parse-jellyfin-item item)]
      (is (instance? LocalDate (::media/premiere parsed)))
      ;; Should be roughly today (allowing for test execution time)
      (is (<= (.toEpochDay (::media/premiere parsed))
              (.toEpochDay (LocalDate/now)))))))

(deftest parse-jellyfin-item-normalizes-type-test
  (testing "parse-jellyfin-item converts Type to lowercase keyword"
    (let [movie (jellyfin/parse-jellyfin-item {:Type "Movie" :Id "1" :Name "Test"})
          series (jellyfin/parse-jellyfin-item {:Type "Series" :Id "2" :Name "Test"})]
      (is (= :movie (::media/type movie)))
      (is (= :series (::media/type series))))))
