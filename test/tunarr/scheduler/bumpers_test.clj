(ns tunarr.scheduler.bumpers-test
  (:require [clojure.test :refer [deftest is]]
            [tunarr.scheduler.bumpers :as bumpers]))

(deftest create-service-test
  (let [service (bumpers/create-service {:tunabrain :mock
                                         :output-dir "/tmp/tunarr-bumpers-test"})]
    ;; create-service wires the config into a service map; it no longer echoes
    ;; its input back unchanged.
    (is (= :mock (:tunabrain service)))
    (is (= "/tmp/tunarr-bumpers-test" (:output-dir service)))
    (is (contains? service :music-library-dir))
    ;; No :grout config supplied, so the Grout upload client stays disabled.
    (is (nil? (:grout service)))))

(deftest create-service-with-grout-test
  (let [service (bumpers/create-service {:tunabrain :mock
                                         :output-dir "/tmp/tunarr-bumpers-test"
                                         :grout {:base-url "http://grout:8080"}})]
    ;; A base-url produces a configured Grout client map.
    (is (= {:base-url "http://grout:8080"} (:grout service)))))

(deftest close-service-test
  (is (nil? (bumpers/close! {:tunabrain :mock}))))
