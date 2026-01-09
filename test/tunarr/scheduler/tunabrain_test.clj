(ns tunarr.scheduler.tunabrain-test
  (:require [clojure.test :refer [deftest is testing]]
            [tunarr.scheduler.tunabrain :as tunabrain]
            [tunarr.scheduler.media :as media]
            [clj-http.client :as http])
  (:import [tunarr.scheduler.tunabrain TunabrainClient]))

;; Note: These tests use mocking since we don't want to make actual HTTP requests

(deftest create-client-test
  (testing "create! creates a tunabrain client with default endpoint"
    (let [client (tunabrain/create! {})]
      (is (instance? TunabrainClient client))
      (is (= "http://localhost:8080" (:endpoint client)))))

  (testing "create! creates a tunabrain client with custom endpoint"
    (let [client (tunabrain/create! {:endpoint "https://tunabrain.example.com"})]
      (is (instance? TunabrainClient client))
      (is (= "https://tunabrain.example.com" (:endpoint client)))))

  (testing "create! sanitizes trailing slashes from endpoint"
    (let [client (tunabrain/create! {:endpoint "https://tunabrain.example.com///"})]
      (is (= "https://tunabrain.example.com" (:endpoint client))))))

(deftest create-client-with-http-opts-test
  (testing "create! accepts custom HTTP options"
    (let [http-opts {:timeout 5000 :socket-timeout 10000}
          client (tunabrain/create! {:endpoint "https://example.com"
                                     :http-opts http-opts})]
      (is (= http-opts (:http-opts client))))))

(deftest request-tags-simple-response-test
  (testing "request-tags! parses simple string array response"
    (with-redefs [http/post (fn [_ _]
                             {:status 200
                              :body "[\"action\", \"adventure\", \"thriller\"]"})]
      (let [client (tunabrain/create! {:endpoint "http://test.local"})
            media {::media/name "Test Movie"
                   ::media/overview "A test movie"}
            result (tunabrain/request-tags! client media)]
        (is (= [:action :adventure :thriller] (:tags result)))))))

(deftest request-tags-detailed-response-test
  (testing "request-tags! parses detailed map response with filtered tags"
    (with-redefs [http/post (fn [_ _]
                             {:status 200
                              :body "{\"tags\": [\"action\", \"adventure\"],
                                      \"filtered_tags\": [\"inappropriate\"],
                                      \"taglines\": [\"An epic journey\"]}"})]
      (let [client (tunabrain/create! {:endpoint "http://test.local"})
            media {::media/name "Test Movie"}
            result (tunabrain/request-tags! client media)]
        (is (= [:action :adventure] (:tags result)))
        (is (= [:inappropriate] (:filtered-tags result)))
        (is (= ["An epic journey"] (:taglines result)))))))

(deftest request-tags-with-catalog-tags-test
  (testing "request-tags! includes existing catalog tags in request"
    (let [posted-data (atom nil)]
      (with-redefs [http/post (fn [_ opts]
                               (reset! posted-data (clojure.walk/keywordize-keys
                                                   (cheshire.core/parse-string (:body opts))))
                               {:status 200
                                :body "[\"action\"]"})]
        (let [client (tunabrain/create! {:endpoint "http://test.local"})
              media {::media/name "Test Movie"}]
          (tunabrain/request-tags! client media :catalog-tags [:existing-tag])
          (is (= [:existing-tag] (:existing_tags @posted-data))))))))

(deftest request-tags-error-handling-test
  (testing "request-tags! throws on non-2xx response"
    (with-redefs [http/post (fn [_ _]
                             {:status 500
                              :body "Internal Server Error"})]
      (let [client (tunabrain/create! {:endpoint "http://test.local"})
            media {::media/name "Test Movie"}]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                             #"tunabrain request failed: 500"
                             (tunabrain/request-tags! client media)))))))

(deftest request-categorization-test
  (testing "request-categorization! parses categorization response"
    (with-redefs [http/post (fn [_ _]
                             {:status 200
                              :body "{\"mappings\": [{\"channel_name\": \"action_channel\",
                                                       \"reasons\": [\"High action content\"]}],
                                      \"dimensions\": {\"mood\": {\"dimension\": \"mood\",
                                                                  \"values\": [{\"value\": \"exciting\",
                                                                              \"reasons\": [\"Fast paced\"]}]}}}"})]
      (let [client (tunabrain/create! {:endpoint "http://test.local"})
            media {::media/name "Test Movie"}
            result (tunabrain/request-categorization! client media
                                                     :categories [:mood]
                                                     :channels [:action-channel :drama-channel])]
        (is (= 1 (count (:mappings result))))
        (is (= :action_channel (get-in result [:mappings 0 ::media/channel-name])))
        (is (= "High action content" (get-in result [:mappings 0 ::media/rationale])))
        (is (contains? (:dimensions result) :mood))
        (is (= :exciting (get-in result [:dimensions :mood 0 ::media/category-value])))))))

(deftest request-tag-triage-test
  (testing "request-tag-triage! parses triage response"
    (with-redefs [http/post (fn [_ _]
                             {:status 200
                              :body "{\"decisions\": [{\"tag\": \"action\",
                                                       \"action\": \"keep\",
                                                       \"reason\": \"Commonly used\"},
                                                      {\"tag\": \"inappropriate\",
                                                       \"action\": \"remove\",
                                                       \"reason\": \"Violates policy\"}]}"})]
      (let [client (tunabrain/create! {:endpoint "http://test.local"})
            tag-samples [{:tag "action" :usage_count 100 :example_titles ["Movie 1"]}
                        {:tag "inappropriate" :usage_count 5 :example_titles ["Movie 2"]}]
            result (tunabrain/request-tag-triage! client tag-samples :target-limit 50 :debug true)]
        (is (= 2 (count (:decisions result))))
        (is (= :keep (get-in result [:decisions 0 :action])))
        (is (= :remove (get-in result [:decisions 1 :action])))))))

(deftest request-tag-audit-test
  (testing "request-tag-audit! parses audit response"
    (with-redefs [http/post (fn [_ _]
                             {:status 200
                              :body "{\"recommended_for_removal\": [{\"tag\": \"inappropriate\",
                                                                     \"reason\": \"Violates content policy\"}]}"})]
      (let [client (tunabrain/create! {:endpoint "http://test.local"})
            tags [:action :comedy :inappropriate]
            result (tunabrain/request-tag-audit! client tags)]
        (is (= 1 (count (:recommended-for-removal result))))
        (is (= "inappropriate" (get-in result [:recommended-for-removal 0 :tag])))
        (is (= "Violates content policy" (get-in result [:recommended-for-removal 0 :reason])))))))

(deftest request-tag-audit-converts-keywords-to-strings-test
  (testing "request-tag-audit! converts keyword tags to strings in request"
    (let [posted-data (atom nil)]
      (with-redefs [http/post (fn [_ opts]
                               (reset! posted-data (clojure.walk/keywordize-keys
                                                   (cheshire.core/parse-string (:body opts))))
                               {:status 200
                                :body "{\"recommended_for_removal\": []}"})]
        (let [client (tunabrain/create! {:endpoint "http://test.local"})]
          (tunabrain/request-tag-audit! client [:action :comedy])
          (is (= ["action" "comedy"] (:tags @posted-data))))))))

(deftest client-implements-closeable-test
  (testing "TunabrainClient implements Closeable"
    (let [client (tunabrain/create! {})]
      (is (instance? java.io.Closeable client))
      ;; Should not throw
      (.close client))))

(deftest endpoint-construction-test
  (testing "requests construct correct endpoint URLs"
    (let [called-urls (atom [])]
      (with-redefs [http/post (fn [url _]
                               (swap! called-urls conj url)
                               {:status 200 :body "[]"})]
        (let [client (tunabrain/create! {:endpoint "https://api.example.com"})
              media {::media/name "Test"}]
          (tunabrain/request-tags! client media)
          (is (= "https://api.example.com/tags" (first @called-urls)))

          (reset! called-urls [])
          (tunabrain/request-categorization! client media)
          (is (= "https://api.example.com/categorize" (first @called-urls)))

          (reset! called-urls [])
          (tunabrain/request-tag-triage! client [])
          (is (= "https://api.example.com/tag-governance/triage" (first @called-urls)))

          (reset! called-urls [])
          (tunabrain/request-tag-audit! client [:tag])
          (is (= "https://api.example.com/tags/audit" (first @called-urls))))))))

(deftest http-options-passed-through-test
  (testing "HTTP options are passed through to clj-http"
    (let [received-opts (atom nil)]
      (with-redefs [http/post (fn [_ opts]
                               (reset! received-opts opts)
                               {:status 200 :body "[]"})]
        (let [client (tunabrain/create! {:endpoint "http://test.local"
                                        :http-opts {:timeout 5000}})
              media {::media/name "Test"}]
          (tunabrain/request-tags! client media)
          (is (= 5000 (:timeout @received-opts))))))))
