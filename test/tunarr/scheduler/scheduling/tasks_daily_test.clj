(ns tunarr.scheduler.scheduling.tasks-daily-test
  "run-daily! must report what each PV rebuild job actually did. PV's rebuild
   endpoint answers 202 + job, and treating that as success hid failed and
   skipped channels behind `:ok`."
  (:require [clojure.test :refer [deftest testing is]]
            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.scheduling.tasks :as tasks]
            [tunarr.scheduler.backends.pseudovision.client :as pv]))

(def channels
  {:ok-chan     {::media/channel-id "u-ok"}
   :failed-chan {::media/channel-id "u-failed"}
   :skip-chan   {::media/channel-id "u-skip"}
   :missing     {::media/channel-id "u-missing"}
   :no-id       {}})

(def jobs
  {"j-ok"     {:id "j-ok" :status "succeeded"
               :result {:events-generated 120 :horizon-days 14 :from "horizon"}}
   "j-failed" {:id "j-failed" :status "failed"
               :error {:message "Build failed at slot 3: boom"}}
   "j-skip"   {:id "j-skip" :status "succeeded"
               :result {:events-generated 0
                        :warning "no schedule attached; nothing to build"}}})

(def job-for-pv-id {1 "j-ok" 2 "j-failed" 3 "j-skip"})

(deftest daily-reports-per-channel-job-outcomes
  (let [submitted (atom [])]
    (with-redefs [pv/list-channels    (fn [_] [{:uuid "u-ok" :id 1}
                                               {:uuid "u-failed" :id 2}
                                               {:uuid "u-skip" :id 3}])
                  pv/rebuild-playout! (fn [_ pv-id opts]
                                        (swap! submitted conj [pv-id opts])
                                        {:job {:id (job-for-pv-id pv-id) :status "queued"}})
                  pv/get-job          (fn [_ job-id] (get jobs job-id))]
      (let [res (tasks/run-daily! {:pseudovision {:base-url "http://pv"}
                                   :channels channels}
                                  :horizon 14)]
        (testing "a succeeded job reports its event count"
          (is (= {:status "ok" :job-id "j-ok" :events-generated 120}
                 (:ok-chan res))))
        (testing "a failed job is reported as failed with PV's message"
          (is (= "failed" (get-in res [:failed-chan :status])))
          (is (= "Build failed at slot 3: boom" (get-in res [:failed-chan :error]))))
        (testing "a channel PV skipped carries PV's warning"
          (is (= "ok" (get-in res [:skip-chan :status])))
          (is (re-find #"no schedule" (get-in res [:skip-chan :warning]))))
        (testing "config problems are still reported"
          (is (= :not-found (:missing res)))
          (is (= :no-channel-id (:no-id res))))
        (testing "rebuilds extend from the horizon"
          (is (every? #(= {:from "horizon" :horizon 14} (second %)) @submitted)))))))

(deftest await-job-times-out
  (with-redefs [pv/get-job (fn [_ id] {:id id :status "running"})]
    (is (= {:id "j" :status "timeout"}
           (pv/await-job! {:base-url "http://pv"} "j" :timeout-ms 0 :poll-ms 1)))))
