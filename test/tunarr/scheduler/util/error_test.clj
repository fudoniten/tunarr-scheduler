(ns tunarr.scheduler.util.error-test
  (:require [clojure.test :refer [deftest is testing]]
            [tunarr.scheduler.util.error :as error]
            [clojure.string :as str]))

(deftest capture-stack-trace-test
  (testing "capture-stack-trace captures exception stack trace as string"
    (let [ex (RuntimeException. "Test exception")
          trace (error/capture-stack-trace ex)]
      (is (string? trace))
      (is (str/includes? trace "RuntimeException"))
      (is (str/includes? trace "Test exception")))))

(deftest capture-stack-trace-includes-cause-test
  (testing "capture-stack-trace includes cause information"
    (let [cause (IllegalArgumentException. "Cause exception")
          ex (RuntimeException. "Wrapper exception" cause)
          trace (error/capture-stack-trace ex)]
      (is (str/includes? trace "RuntimeException"))
      (is (str/includes? trace "Wrapper exception"))
      (is (str/includes? trace "IllegalArgumentException"))
      (is (str/includes? trace "Cause exception")))))

(deftest capture-stack-trace-includes-file-and-line-test
  (testing "capture-stack-trace includes file and line number information"
    (let [ex (try
               (throw (Exception. "Test error"))
               (catch Exception e e))
          trace (error/capture-stack-trace ex)]
      ;; Stack traces typically include class names and line numbers
      (is (str/includes? trace "clojure.")))))

(deftest capture-stack-trace-handles-nil-message-test
  (testing "capture-stack-trace handles exceptions with nil message"
    (let [ex (RuntimeException.)
          trace (error/capture-stack-trace ex)]
      (is (string? trace))
      (is (str/includes? trace "RuntimeException")))))

(deftest capture-stack-trace-preserves-multiline-test
  (testing "capture-stack-trace preserves multiline stack traces"
    (let [ex (RuntimeException. "Test exception")
          trace (error/capture-stack-trace ex)
          lines (str/split-lines trace)]
      ;; Stack traces typically have multiple lines
      (is (> (count lines) 1)))))
