(ns tunarr.scheduler.http.api.scheduling-test
  "Coverage for the channel selectors and strict param handling on the periodic
   scheduling endpoints.

   The channel config keys are keywords, but the ?channel query param arrives as
   a string — if the filter matched on raw keys, a string param would never
   match a keyword key, the channel map would be silently emptied, and the task
   would run against zero channels (so Tunabrain is never called even though the
   job 'succeeds'). Channels may also be selected by ?channel_id, and any
   unrecognized query param or unresolvable selector must fail fast with a 400."
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

(defn- req
  "Build a request matching what the middleware chain produces: `coerced` is the
   keyword-keyed :parameters/:query view (post-coercion, stripped), `raw` is the
   string-keyed :query-params view (pre-coercion) used for unknown-param checks.
   `raw` defaults to a string-keyed mirror of `coerced`."
  ([coerced] (req coerced (into {} (map (fn [[k v]] [(name k) v])) coerced)))
  ([coerced raw] {:parameters {:query coerced} :query-params raw}))

(defn- await-job [job-id]
  (loop [remaining 2000]
    (let [info (runner/job-info *job-runner* job-id)]
      (cond
        (#{:succeeded :failed} (:status info)) info
        (pos? remaining) (do (Thread/sleep 20) (recur (- remaining 20)))
        :else info))))

;; ── ?channel (by config key name) ───────────────────────────────────────────

(deftest quarterly-channel-name-matches-keyword-key
  (testing "?channel=enigma narrows to the keyword-keyed :enigma channel"
    (let [seen (atom nil)]
      (with-redefs [tasks/run-quarterly! (fn [c & _] (reset! seen (:channels c)) {})]
        (let [resp ((scheduling/quarterly-handler (ctx)) (req {:channel "enigma"}))]
          (is (= 202 (:status resp)))
          (await-job (get-in resp [:body :job :id]))
          (is (= #{:enigma} (set (keys @seen)))
              "the requested channel must survive filtering (the bug that left Tunabrain uncalled)"))))))

;; ── ?channel_id (by ::media/channel-id) ──────────────────────────────────────

(deftest quarterly-channel-id-selects-channel
  (testing "?channel_id=uuid-prime selects the channel whose ::media/channel-id matches"
    (let [seen (atom nil)]
      (with-redefs [tasks/run-quarterly! (fn [c & _] (reset! seen (:channels c)) {})]
        (let [resp ((scheduling/quarterly-handler (ctx)) (req {:channel_id "uuid-prime"}))]
          (is (= 202 (:status resp)))
          (await-job (get-in resp [:body :job :id]))
          (is (= #{:prime} (set (keys @seen)))))))))

(deftest quarterly-channel-and-id-union
  (testing "?channel and ?channel_id together select the union of both"
    (let [seen (atom nil)]
      (with-redefs [tasks/run-quarterly! (fn [c & _] (reset! seen (:channels c)) {})]
        (let [resp ((scheduling/quarterly-handler (ctx))
                    (req {:channel "enigma" :channel_id "uuid-prime"}))]
          (is (= 202 (:status resp)))
          (await-job (get-in resp [:body :job :id]))
          (is (= #{:enigma :prime} (set (keys @seen)))))))))

;; ── No selector → all channels ───────────────────────────────────────────────

(deftest quarterly-no-selector-runs-all-channels
  (testing "omitting selectors runs against every configured channel"
    (let [seen (atom nil)]
      (with-redefs [tasks/run-quarterly! (fn [c & _] (reset! seen (:channels c)) {})]
        (let [resp ((scheduling/quarterly-handler (ctx)) (req {}))]
          (is (= 202 (:status resp)))
          (await-job (get-in resp [:body :job :id]))
          (is (= #{:enigma :prime} (set (keys @seen)))))))))

;; ── ?date (target quarter selection) ─────────────────────────────────────────

(deftest quarterly-date-param-forwarded-to-task
  (testing "?date is forwarded to run-quarterly! so it can pick the target quarter"
    (let [seen-date (atom :unset)]
      (with-redefs [tasks/run-quarterly! (fn [_ & {:keys [date]}] (reset! seen-date date) {})]
        (let [resp ((scheduling/quarterly-handler (ctx))
                    (req {:date "2026-10-01"} {"date" "2026-10-01"}))]
          (is (= 202 (:status resp)))
          (await-job (get-in resp [:body :job :id]))
          (is (= "2026-10-01" @seen-date)))))))

;; ── Fail fast: unresolvable selectors ────────────────────────────────────────

(deftest quarterly-unknown-channel-name-fails-fast
  (testing "an unknown ?channel returns 400 instead of silently launching a no-op job"
    (let [called (atom false)]
      (with-redefs [tasks/run-quarterly! (fn [_ & _] (reset! called true) {})]
        (let [resp ((scheduling/quarterly-handler (ctx)) (req {:channel "nonexistent"}))]
          (is (= 400 (:status resp)))
          (is (re-find #"unknown channel" (get-in resp [:body :error])))
          (is (false? @called) "no job should run for an unknown channel"))))))

(deftest quarterly-unknown-channel-id-fails-fast
  (testing "an unknown ?channel_id returns 400"
    (let [called (atom false)]
      (with-redefs [tasks/run-quarterly! (fn [_ & _] (reset! called true) {})]
        (let [resp ((scheduling/quarterly-handler (ctx)) (req {:channel_id "uuid-bogus"}))]
          (is (= 400 (:status resp)))
          (is (re-find #"channel_id=uuid-bogus" (get-in resp [:body :error])))
          (is (false? @called)))))))

;; ── Fail fast: unrecognized query params ─────────────────────────────────────

(deftest quarterly-unrecognized-param-fails-fast
  (testing "a typo'd/unknown query param returns 400 (coercion would otherwise strip it silently)"
    (let [called (atom false)]
      (with-redefs [tasks/run-quarterly! (fn [_ & _] (reset! called true) {})]
        ;; coercion strips "chanel" from :parameters, but it survives in :query-params
        (let [resp ((scheduling/quarterly-handler (ctx)) (req {} {"chanel" "enigma"}))]
          (is (= 400 (:status resp)))
          (is (re-find #"unrecognized query param" (get-in resp [:body :error])))
          (is (false? @called)))))))

(deftest daily-allows-horizon-but-rejects-others
  (testing "daily accepts ?horizon, but still rejects unknown params"
    (with-redefs [tasks/run-daily! (fn [_ & _] {})]
      (let [ok ((scheduling/daily-handler (ctx)) (req {:horizon 30} {"horizon" "30"}))]
        (is (= 200 (:status ok))))
      (let [bad ((scheduling/daily-handler (ctx)) (req {} {"horzon" "30"}))]
        (is (= 400 (:status bad)))
        (is (re-find #"unrecognized query param" (get-in bad [:body :error])))))))

;; ── Same behavior on the synchronous weekly endpoint ─────────────────────────

(deftest weekly-shares-selector-and-strict-param-behavior
  (testing "weekly applies the same channel filter and strict param checks"
    (let [seen (atom nil)]
      (with-redefs [tasks/run-weekly! (fn [c] (reset! seen (:channels c)) {})]
        (let [ok ((scheduling/weekly-handler (ctx)) (req {:channel "enigma"}))]
          (is (= 200 (:status ok)))
          (is (= #{:enigma} (set (keys @seen)))))
        (let [bad ((scheduling/weekly-handler (ctx)) (req {} {"nope" "1"}))]
          (is (= 400 (:status bad))))))))
