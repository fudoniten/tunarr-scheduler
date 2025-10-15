(ns tunarr.scheduler.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [tunarr.scheduler.config :as config]))

(deftest parse-port-test
  (testing "integer values are returned unchanged"
    (is (= 8080 (#'config/parse-port 8080))))
  (testing "string values are parsed after trimming"
    (is (= 9000 (#'config/parse-port " 9000 "))))
  (testing "unsupported types throw"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unsupported port value"
                          (#'config/parse-port {:not "a-port"}))))))

(deftest load-config-default-test
  (let [cfg (config/load-config)]
    (is (= 8080 (get-in cfg [:server :port])))))

(deftest load-config-custom-test
  (let [tmp-file (java.io.File/createTempFile "tunarr-config" ".edn")
        _ (spit tmp-file (pr-str {:server {:port " 1234 "}}))
        cfg (config/load-config (.getAbsolutePath tmp-file))]
    (.delete tmp-file)
    (is (= 1234 (get-in cfg [:server :port])))))

(deftest config->system-defaults-test
  (let [system (config/config->system {:server {:port 3000}})]
    (is (= {:type :memory} (:tunarr/catalog system)))
    (is (= 3000 (get-in system [:tunarr/http-server :port])))))

(deftest config->system-postgres-defaults-test
  (let [system (config/config->system {:server {:port 3000}
                                       :catalog {:type "postgresql"
                                                 :password "secret"}})
        catalog (:tunarr/catalog system)]
    (is (= :postgresql (:type catalog)))
    (is (= "tunarr-scheduler" (:dbname catalog)))
    (is (= "tunarr-scheduler" (:user catalog)))
    (is (= "secret" (:password catalog)))
    (is (= "postgres" (:host catalog)))
    (is (= 5432 (:port catalog)))))
