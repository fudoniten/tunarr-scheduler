(ns tunarr.scheduler.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [tunarr.scheduler.config :as config]))

(deftest parse-port-test
  (testing "integer values are returned unchanged"
    (is (= 8080 (#'config/parse-port 8080))))
  (testing "string values are parsed after trimming"
    (is (= 9000 (#'config/parse-port " 9000 "))))
  (testing "unsupported types throw"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unsupported port value"
                          (#'config/parse-port {:not "a-port"})))))

(deftest load-config-default-test
  (let [cfg (config/load-config "resources/config.edn")]
    (is (= 8080 (get-in cfg [:server :port])))))

(deftest load-config-custom-test
  (let [tmp-file (java.io.File/createTempFile "tunarr-config" ".edn")
        _ (spit tmp-file (pr-str {:server {:port " 1234 "}}))
        cfg (config/load-config (.getAbsolutePath tmp-file))]
    (.delete tmp-file)
    (is (= 1234 (get-in cfg [:server :port])))))

(deftest config->system-defaults-test
  (let [system (config/config->system {:server {:port 3000}})]
    ;; The raw catalog carries the catalog config; :tunarr/catalog is the
    ;; auto-sync wrapper that other components depend on.
    (is (= {:type :memory} (:tunarr/raw-catalog system)))
    (is (= (ig/ref :tunarr/raw-catalog) (get-in system [:tunarr/catalog :catalog])))
    (is (= (ig/ref :tunarr/pseudovision) (get-in system [:tunarr/catalog :pseudovision])))
    (is (= {} (:tunarr/job-runner system)))
    (is (= 3000 (get-in system [:tunarr/http-server :port])))))

(deftest config->system-job-runner-config-test
  (let [system (config/config->system {:server {:port 3000}
                                       :jobs {:max-concurrency 8}})]
    (is (= {:max-concurrency 8} (:tunarr/job-runner system)))
    (is (= (ig/ref :tunarr/job-runner)
           (get-in system [:tunarr/http-server :job-runner])))))

(deftest config->system-postgres-defaults-test
  (let [system (config/config->system {:server {:port 3000}
                                       :catalog {:type "postgresql"
                                                 :password "secret"}})
        catalog (:tunarr/raw-catalog system)]
    (is (= :postgresql (:type catalog)))
    (is (= "tunarr-scheduler" (:dbname catalog)))
    (is (= "tunarr-scheduler" (:user catalog)))
    (is (= "secret" (:password catalog)))
    (is (= "postgres" (:host catalog)))
    (is (= 5432 (:port catalog)))))
