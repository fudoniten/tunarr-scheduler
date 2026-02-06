(ns tunarr.scheduler.sql.executor
  (:require [clojure.core.async :refer [chan >!! <!!] :as async]
            [taoensso.timbre :as log :refer [log]]
            [honey.sql :as sql]
            [next.jdbc :as jdbc])
  (:import [java.sql SQLException]))

(defprotocol ISQLExecutor
  (exec! [self query])
  (exec-with-tx! [self queries])
  (fetch! [self query])
  (close! [self]))

(defn log-query [query & {:keys [log-level]
                          :or {log-level :info}}]
  (log log-level query)
  query)

(defn classify-sql-exception
  [e]
  (let [state (.getSQLState e)
        cls (some-> state (subs 0 2))]
    (cond
      (#{ "40001" "40P01" } state)   :retryable
      (= "08" cls)                   :retryable   ; connection
      (= "53" cls)                   :retry-later ; resources
      (= "57" cls)                   :retry-later ; canceled/etc.
      (= "22" cls)                   :bad-input
      (= "42" cls)                   :bad-input
      (= state "23505")              :constraint
      (= state "23503")              :constraint
      (= state "23502")              :constraint
      (= state "23514")              :constraint
      :else                          :unknown)))

(defmulti execute-job! ::job-type)

(defmethod execute-job! ::fetch
  [{:keys [query conn result]}]
  (if-not (map? query)
    (deliver result [:err (ex-info "::fetch requires an individual statement" {:query query})])
    (try
      (let [data (jdbc/execute! conn (log-query (sql/format query)))]
        (deliver result [:ok data]))
      (catch SQLException e
        (deliver result [:err (ex-info (format "failed to run sql query: %s"
                                               (.getMessage e))
                                       {:query query
                                        :error e
                                        :cause (classify-sql-exception e)})]))
      (catch Throwable e
        (deliver result [:err e])))))

(defmethod execute-job! ::execute
  [{:keys [query conn result]}]
  (if-not (map? query)
    (deliver result [:err (ex-info "::execute requires an individual statement" {:query query})])
    (try
      (jdbc/execute! conn (log-query (sql/format query)))
      (deliver result [:ok true])
      (catch SQLException e
        (deliver result [:err (ex-info (format "failed to run sql query: %s"
                                               (.getMessage e))
                                       {:error e
                                        :cause (classify-sql-exception e)})]))
      (catch Throwable e
        (deliver result [:err e])))))

(defmethod execute-job! ::execute-with-transaction
  [{:keys [queries conn result]}]
  (if-not (sequential? queries)
    (deliver result [:err (ex-info "::execute-with-transaction requires collection of queries" {:query queries})])
    (try
      (jdbc/with-transaction [tx conn]
        (doseq [query queries]
          (jdbc/execute! tx (log-query (sql/format query))))
        (deliver result [:ok true]))
      (catch SQLException e
        (deliver result [:err (ex-info (format "failed to run sql query: %s"
                                               (.getMessage e))
                                       {:error e
                                        :cause (classify-sql-exception e)})]))
      (catch Throwable e
        (deliver result [:err e])))))

(defn create-executor
  [store & {:keys [queue-size worker-count]
            :or {queue-size   30
                 worker-count 10}}]
  (let [jobs (chan (async/buffer queue-size))
        workers (doall
                 (for [i (range worker-count)]
                   (future
                     (log/info (format "starting sql executor worker #%s" i))
                     (with-open [conn (jdbc/get-connection store)]
                       (try
                         (loop [{:keys [result] :as job} (<!! jobs)]
                           (try (when job
                                  (execute-job! (assoc job :conn conn)))
                                (catch Throwable t
                                  (deliver result [:err (ex-info (format "failed to execute job: %s"
                                                                         (.getMessage t))
                                                                 {:error t})])))
                           (if job
                             (recur (<!! jobs))
                             (log/info (format "shutting down sql executor worker  #%s" i))))
                         (catch Throwable t
                           (log/error t "worker crashed, exiting")))))))]
    (reify ISQLExecutor
      (exec! [_ query]
        (let [result (promise)]
          (if (>!! jobs {:query query :result result ::job-type ::execute})
            result
            (do (deliver result [:err (ex-info "executor channel is closed" {})])
                result))))
      (exec-with-tx! [_ queries]
        (let [result (promise)]
          (if (>!! jobs {:queries queries :result result ::job-type ::execute-with-transaction})
            result
            (do (deliver result [:err (ex-info "executor channel is closed" {})])
                result))))
      (fetch! [_ query]
        (let [result (promise)]
          (if (>!! jobs {:query query :result result ::job-type ::fetch})
            result
            (do (deliver result [:err (ex-info "executor channel is closed" {})])
                result))))
      (close! [_]
        (async/close! jobs)
        (doseq [w workers] @w)))))
