(ns tunarr.scheduler.util.time-test
  (:require [clojure.test :refer [deftest is testing]]
            [tunarr.scheduler.util.time :as time]
            [tick.core :as t]))

(deftest daytime-within-hours-test
  (testing "daytime? returns true for times within window"
    (let [time-zone "America/New_York"
          start-hour 9
          end-hour 17
          instant (t/instant "2024-01-15T14:30:00Z")] ; 9:30 AM EST
      (is (time/daytime? time-zone start-hour end-hour instant)))))

(deftest daytime-before-hours-test
  (testing "daytime? returns false for times before window"
    (let [time-zone "America/New_York"
          start-hour 9
          end-hour 17
          instant (t/instant "2024-01-15T12:00:00Z")] ; 7:00 AM EST
      (is (not (time/daytime? time-zone start-hour end-hour instant))))))

(deftest daytime-after-hours-test
  (testing "daytime? returns false for times after window"
    (let [time-zone "America/New_York"
          start-hour 9
          end-hour 17
          instant (t/instant "2024-01-15T23:00:00Z")] ; 6:00 PM EST
      (is (not (time/daytime? time-zone start-hour end-hour instant))))))

(deftest daytime-at-start-boundary-test
  (testing "daytime? returns true at start hour boundary"
    (let [time-zone "America/New_York"
          start-hour 9
          end-hour 17
          instant (t/instant "2024-01-15T14:00:00Z")] ; 9:00 AM EST
      (is (time/daytime? time-zone start-hour end-hour instant)))))

(deftest daytime-at-end-boundary-test
  (testing "daytime? returns true at end hour boundary"
    (let [time-zone "America/New_York"
          start-hour 9
          end-hour 17
          instant (t/instant "2024-01-15T22:00:00Z")] ; 5:00 PM EST
      (is (time/daytime? time-zone start-hour end-hour instant)))))

(deftest daytime-different-timezone-test
  (testing "daytime? works with different time zones"
    (let [time-zone "Asia/Tokyo"
          start-hour 10
          end-hour 18
          instant (t/instant "2024-01-15T06:00:00Z")] ; 3:00 PM JST
      (is (time/daytime? time-zone start-hour end-hour instant)))))

(deftest daytime-utc-timezone-test
  (testing "daytime? works with UTC timezone"
    (let [time-zone "UTC"
          start-hour 8
          end-hour 20
          instant (t/instant "2024-01-15T15:00:00Z")] ; 3:00 PM UTC
      (is (time/daytime? time-zone start-hour end-hour instant)))))

(deftest daytime-precondition-violated-test
  (testing "daytime? throws when start-hour >= end-hour"
    (let [time-zone "UTC"
          instant (t/instant "2024-01-15T12:00:00Z")]
      (is (thrown? AssertionError
                   (time/daytime? time-zone 17 9 instant)))
      (is (thrown? AssertionError
                   (time/daytime? time-zone 12 12 instant))))))

(deftest daytime-early-morning-test
  (testing "daytime? works for early morning hours"
    (let [time-zone "UTC"
          start-hour 0
          end-hour 6
          instant (t/instant "2024-01-15T03:00:00Z")] ; 3:00 AM UTC
      (is (time/daytime? time-zone start-hour end-hour instant)))))

(deftest daytime-late-night-test
  (testing "daytime? works for late night hours"
    (let [time-zone "UTC"
          start-hour 18
          end-hour 23
          instant (t/instant "2024-01-15T21:00:00Z")] ; 9:00 PM UTC
      (is (time/daytime? time-zone start-hour end-hour instant)))))
