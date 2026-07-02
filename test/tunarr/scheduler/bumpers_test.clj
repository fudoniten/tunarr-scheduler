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
    ;; No :jellyfin config supplied, so the Jellyfin client stays disabled.
    (is (nil? (:jellyfin service)))))

(deftest close-service-test
  (is (nil? (bumpers/close! {:tunabrain :mock}))))
