(ns tunarr.scheduler.http.api.scheduling-test
  "Regression coverage for the ?channel filter on the periodic scheduling
   endpoints. The channel config keys are keywords, but the query param arrives
   as a string — if the filter matched on raw keys, a string param would never
   match a keyword key, the channel map would be silently emptied, and the task
   would run against zero channels (so Tunabrain is never called even though the
   job 'succeeds')."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [tunarr.scheduler.http.api.scheduling :as scheduling]
            [tunarr.scheduler.scheduling.tasks :as tasks]
            [tunarr.scheduler.jobs.runner :as runner]
            [tunarr.scheduler.media :as media]))

(def ^:dynamic *job-runner* nil)

(defn test-fixture [f]
  (let [job-runner (runner/create {})]
    (binding [*job-runner* job-runner]
      (try (f) (finally (runner/shutdown! job-runner))))))

(use-fixtures :each test-fixture)

(def channels
  {:enigma {::media/channel-id "uuid-enigma"
            ::media/channel-fullname "Enigma"
            ::media/channel-description "Mystery programming"}
   :prime  {::media/channel-id "uuid-prime"
            ::media/channel-fullname "Prime"
            ::media/channel-description "Primetime programming"}})

(defn- ctx []
  {:job-runner *job-runner* :channels channels})

(defn- req [channel-param]
  {:parameters {:query (when channel-param {:channel channel-param})}})

(defn- await-job [job-id]
  (loop [remaining 2000]
    (let [info (runner/job-info *job-runner* job-id)]
      (cond
        (#{:succeeded :failed} (:status info)) info
        (pos? remaining) (do (Thread/sleep 20) (recur (- remaining 20)))
        :else info))))

(deftest quarterly-string-param-matches-keyword-channel-key
  (testing "?channel=enigma narrows to the keyword-keyed :enigma channel and the task receives it"
    (let [seen (atom nil)]
      (with-redefs [tasks/run-quarterly! (fn [ctx'] (reset! seen (:channels ctx')) {:ran true})]
        (let [resp ((scheduling/quarterly-handler (ctx)) (req "enigma"))
              job-id (get-in resp [:body :job :id])]
          (is (= 202 (:status resp)))
          (await-job job-id)
          (is (= #{:enigma} (set (keys @seen)))
              "the requested channel must survive filtering (this is the bug that left Tunabrain uncalled)"))))))

(deftest quarterly-unknown-channel-fails-loudly
  (testing "an unknown ?channel returns 400 instead of silently launching a no-op job"
    (let [called (atom false)]
      (with-redefs [tasks/run-quarterly! (fn [_] (reset! called true) {})]
        (let [resp ((scheduling/quarterly-handler (ctx)) (req "nonexistent"))]
          (is (= 400 (:status resp)))
          (is (re-find #"unknown channel" (get-in resp [:body :error])))
          (is (false? @called) "no job should run for an unknown channel"))))))

(deftest quarterly-no-param-runs-all-channels
  (testing "omitting ?channel runs against every configured channel"
    (let [seen (atom nil)]
      (with-redefs [tasks/run-quarterly! (fn [ctx'] (reset! seen (:channels ctx')) {})]
        (let [resp ((scheduling/quarterly-handler (ctx)) (req nil))
              job-id (get-in resp [:body :job :id])]
          (is (= 202 (:status resp)))
          (await-job job-id)
          (is (= #{:enigma :prime} (set (keys @seen)))))))))

(deftest weekly-string-param-matches-keyword-channel-key
  (testing "the same string-vs-keyword filter fix applies to the synchronous weekly endpoint"
    (let [seen (atom nil)]
      (with-redefs [tasks/run-weekly! (fn [ctx'] (reset! seen (:channels ctx')) {})]
        (let [resp ((scheduling/weekly-handler (ctx)) (req "enigma"))]
          (is (= 200 (:status resp)))
          (is (= #{:enigma} (set (keys @seen)))))))))
