(ns tunarr.scheduler.backends.pseudovision.client-test
  "Wire-level tests for the Pseudovision HTTP client. These stub
   `clj-http.client/request` and inspect the request map that actually goes on
   the wire — the layer the higher-level orchestration tests skip by redefining
   the wrapper fns wholesale."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [tunarr.scheduler.backends.pseudovision.client :as pv]))

(def ^:private config {:base-url "http://pv:8080"})

(defn- capturing
  "Redef http/request to capture the opts and return `resp`. Returns the atom."
  [captured resp f]
  (with-redefs [http/request (fn [opts]
                               (reset! captured opts)
                               (merge {:status 200 :body {}} resp))]
    (f))
  @captured)

;; Regression: the native-schedule sync path (create-collection!, create-schedule!,
;; add-slot!, attach-schedule!, …) built bodies with a `:json-params` key that
;; clj-http does not recognize, so requests went out with no body and PV rejected
;; them with a 400 "Request coercion failed" on body-params. Every JSON-bodied
;; wrapper must put a serialized body on the wire.

(deftest create-collection!-sends-json-body
  (let [captured (atom nil)]
    (capturing captured {:status 201 :body {:id 77}}
               #(pv/create-collection! config {:name "auto:series:42"
                                               :kind "smart"
                                               :config {:query {:show-id 42}}}))
    (testing "no unknown :json-params key leaks to clj-http"
      (is (not (contains? @captured :json-params))))
    (testing "a JSON body actually goes on the wire"
      (is (= :json (:content-type @captured)))
      (is (string? (:body @captured)))
      (is (= {"name" "auto:series:42"
              "kind" "smart"
              "config" {"query" {"show-id" 42}}}
             (json/parse-string (:body @captured)))))))

(deftest create-schedule!-sends-json-body
  (let [captured (atom nil)]
    (capturing captured {:status 201 :body {:id 9}}
               #(pv/create-schedule! config {:name "Q3 2026 — Classic Comedy"}))
    (is (not (contains? @captured :json-params)))
    (is (= {"name" "Q3 2026 — Classic Comedy"}
           (json/parse-string (:body @captured))))))

(deftest attach-schedule!-sends-body-and-preserves-query-params
  (let [captured (atom nil)]
    (capturing captured {:status 200 :body {}}
               #(pv/attach-schedule! config 42 9 :rebuild true :horizon 14))
    (testing "body carries the schedule id"
      (is (= {"schedule-id" 9} (json/parse-string (:body @captured)))))
    (testing "query-params passed alongside the json body survive"
      (is (= {"rebuild" "true" "horizon" "14"} (:query-params @captured))))))
