(ns tunarr.scheduler.bumpers-test
  (:require [clojure.test :refer [deftest is]]
            [tunarr.scheduler.bumpers :as bumpers]))

(deftest create-service-test
  (let [deps {:llm {:type :mock}
              :tts {:type :mock}}
        service (bumpers/create-service deps)]
    (is (= deps service))))

(deftest close-service-test
  (is (nil? (bumpers/close! {:llm :mock :tts :mock}))))
