(ns tunarr.scheduler.llm-test
  (:require [clojure.test :refer [deftest is testing]]
            [tunarr.scheduler.llm :as llm]))

(deftest llm-client?-test
  (testing "llm clients satisfy the protocol"
    (let [client (llm/create-client {:provider "generic"})]
      (is (true? (llm/llm-client? client)))))
  (testing "non-clients do not satisfy the protocol"
    (is (false? (llm/llm-client? {})))))

(deftest create-client-variants-test
  (testing "default client uses provider"
    (let [client (llm/create-client {:provider "generic"})]
      (is (= "generic" (:provider client)))))
  (testing "mock provider returns mock client"
    (let [client (llm/create-client {:provider :mock})]
      (is (= :mock (:provider client)))
      (is (llm/llm-client? client)))))
