(ns tunarr.scheduler.http.middleware-test
  (:require [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]
            [ring.core.protocols :as ring-protocols]
            [tunarr.scheduler.http.middleware :as mw])
  (:import [java.io ByteArrayOutputStream]))

(defn- writable-body?
  "True when `body` is something ring-jetty can actually write to the servlet
   output stream. A raw Clojure map is NOT writable and blows up at the servlet
   layer as an opaque Jetty HTML 500 — the bug this guards against."
  [body]
  (try
    (with-open [o (ByteArrayOutputStream.)]
      (ring-protocols/write-body-to-stream body {} o))
    true
    (catch IllegalArgumentException _ false)))

(deftest wrap-error-handler-returns-writable-json-for-errors
  (testing "an Error (e.g. AbstractMethodError) escaping the handler yields a
            500 whose body ring can actually serialize"
    (let [handler (mw/wrap-error-handler
                   (fn [_] (throw (AbstractMethodError. "boom"))))
          resp    (handler {:request-method :get :uri "/x"})]
      (is (= 500 (:status resp)))
      (is (string? (:body resp))
          "body must be a String, not a raw map (a map is unwritable by ring-jetty)")
      (is (writable-body? (:body resp)))
      (is (= {:error "Internal server error"} (json/parse-string (:body resp) true)))))

  (testing "a plain Exception is likewise caught and rendered as writable JSON"
    (let [handler (mw/wrap-error-handler
                   (fn [_] (throw (RuntimeException. "kaboom"))))
          resp    (handler {:request-method :get :uri "/x"})]
      (is (= 500 (:status resp)))
      (is (writable-body? (:body resp))))))

(deftest exception-middleware-catches-throwable
  (testing "exception-middleware catches Errors too, not just Exceptions"
    (let [handler (mw/exception-middleware
                   (fn [_] (throw (AbstractMethodError. "no such method"))))
          resp    (handler {:request-method :get :uri "/x"})]
      (is (= 500 (:status resp)))
      (is (map? (:body resp)))
      (is (contains? (:body resp) :error)))))
