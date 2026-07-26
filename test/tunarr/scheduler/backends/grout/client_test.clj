(ns tunarr.scheduler.backends.grout.client-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-http.client :as http]
            [tunarr.scheduler.backends.grout.client :as grout])
  (:import [java.io File]))

(def ^:private client {:base-url "http://grout:8080"})

(defn- multipart-field [captured n]
  (some #(when (= n (:name %)) %) (get-in captured [:opts :multipart])))

(deftest create!-test
  (testing "a base-url yields a client map"
    (is (= {:base-url "http://grout:8080"}
           (grout/create! {:base-url "http://grout:8080"}))))
  (testing "blank/absent base-url disables the client"
    (is (nil? (grout/create! {:base-url ""})))
    (is (nil? (grout/create! {:base-url nil})))
    (is (nil? (grout/create! {})))))

(deftest intake!-created-test
  (testing "201 → :created? true and the parsed Media"
    (let [captured (atom nil)
          mp4 (File/createTempFile "grout-intake" ".mp4")]
      (try
        (with-redefs [http/post (fn [url opts]
                                  (reset! captured {:url url :opts opts})
                                  {:status 201 :body {:id "abc" :kind "bumper"}})]
          (let [result (grout/intake! client
                                      {:path (.getAbsolutePath mp4)
                                       :kind "bumper"
                                       :channel "hua"
                                       :tags ["spooky" "retro"]
                                       :source "tunarr-bumper"
                                       :name "Title"
                                       :description "Script"})]
            (is (true? (:created? result)))
            (is (= 201 (:status result)))
            (is (= "abc" (get-in result [:media :id])))
            ;; Request went to the intake route as a multipart upload — the
            ;; file part carries the bytes, everything else is a string field
            ;; (tags comma-joined, per Grout's parser), and there's no JSON
            ;; body/path field at all.
            (is (= "http://grout:8080/grout/media" (:url @captured)))
            (is (nil? (get-in @captured [:opts :body])))
            (is (= (.getAbsolutePath mp4) (.getAbsolutePath (:content (multipart-field @captured "file")))))
            (is (= "bumper" (:content (multipart-field @captured "kind"))))
            (is (= "hua" (:content (multipart-field @captured "channel"))))
            (is (= "spooky,retro" (:content (multipart-field @captured "tags"))))
            (is (nil? (multipart-field @captured "source-url")))))
        (finally (.delete mp4))))))

(deftest intake!-matched-test
  (testing "200 → :created? false (matched existing by hash)"
    (with-redefs [http/post (fn [_ _] {:status 200 :body {:id "dup"}})]
      (let [result (grout/intake! client {:path "/tmp/x.mp4" :kind "bumper"})]
        (is (false? (:created? result)))
        (is (= "dup" (get-in result [:media :id])))))))

(deftest intake!-error-test
  (testing "422 pipeline failure throws with status + response"
    (with-redefs [http/post (fn [_ _] {:status 422 :body {:error "ffprobe failed"}})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Grout intake failed"
            (grout/intake! client {:path "/bad.mp4" :kind "bumper"}))))))

(deftest by-hash-test
  (testing "200 returns Media, 404 returns nil"
    (with-redefs [http/get (fn [_ _] {:status 200 :body {:id "h"}})]
      (is (= {:id "h"} (grout/by-hash client "deadbeef"))))
    (with-redefs [http/get (fn [_ _] {:status 404 :body {:error "not found"}})]
      (is (nil? (grout/by-hash client "deadbeef"))))))

(deftest sha256-file-test
  (testing "hashes file bytes to lowercase hex matching known SHA-256"
    (let [f (File/createTempFile "grout-sha" ".txt")]
      (try
        (spit f "abc")
        ;; SHA-256("abc")
        (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
               (grout/sha256-file (.getAbsolutePath f))))
        (finally (.delete f))))))
