(ns tunarr.scheduler.http.middleware
  "HTTP middleware for JSON handling, exception handling, and logging."
  (:require [muuntaja.core :as m]
            [cheshire.core :as json]
            [taoensso.timbre :as log]
            [tunarr.scheduler.http.util :as util])
  (:import [com.fasterxml.jackson.databind.module SimpleModule]
           [com.fasterxml.jackson.databind JsonSerializer SerializerProvider]
           [com.fasterxml.jackson.core JsonGenerator]
           [java.time LocalDate Instant]
           [java.math BigDecimal]))

;; ---------------------------------------------------------------------------
;; Muuntaja configuration
;; ---------------------------------------------------------------------------

(defn- string-serializer [f]
  (proxy [JsonSerializer] []
    (serialize [value ^JsonGenerator gen ^SerializerProvider _provider]
      (.writeString gen (f value)))))

(defn- bigdecimal-serializer []
  (proxy [JsonSerializer] []
    (serialize [value ^JsonGenerator gen ^SerializerProvider _provider]
      (.writeNumber gen ^BigDecimal value))))

(defn- java-types-module []
  (doto (SimpleModule.)
    (.addSerializer LocalDate  (string-serializer str))
    (.addSerializer Instant    (string-serializer str))
    (.addSerializer BigDecimal (bigdecimal-serializer))))

(def muuntaja
  "JSON encoding/decoding configuration.

   Replaces manual JSON parsing with automatic content negotiation and
   coercion. Configured to use keyword keys for parsed JSON bodies."
  (m/create
   (-> m/default-options
       (assoc-in [:formats "application/json" :decoder-opts]
                 {:keywords? true})
       (assoc-in [:formats "application/json" :encoder-opts]
                 {:modules [(java-types-module)]}))))

;; ---------------------------------------------------------------------------
;; Exception middleware
;; ---------------------------------------------------------------------------

(defn exception-middleware
  "Catches exceptions from handlers and returns structured error responses.
   
   This middleware is part of the Reitit middleware chain and handles
   exceptions thrown during request processing. ExceptionInfo with :status
   in ex-data is used for controlled error responses."
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (log/error e "Handler exception" data)
          {:status (or (:status data) 500)
           :body   {:error (util/error-message e)}}))
      (catch Exception e
        (log/error e "Unexpected exception")
        {:status 500
         :body   {:error (util/error-message e)}}))))

;; ---------------------------------------------------------------------------
;; Request logging
;; ---------------------------------------------------------------------------

(defn wrap-request-logging
  "Logs incoming requests for debugging and monitoring."
  [handler]
  (fn [request]
    (log/debug "HTTP request"
               {:method (:request-method request)
                :uri    (:uri request)
                :query  (:query-string request)})
    (handler request)))

;; ---------------------------------------------------------------------------
;; JSON response wrapper
;; ---------------------------------------------------------------------------

(defn wrap-json-response
  "Ensures response body is JSON encoded if it's a map.
   
   This wrapper catches responses that bypass the Muuntaja middleware
   (like 404/405 handlers) and manually encodes their bodies to JSON."
  [handler]
  (fn [request]
    (let [response (handler request)]
      (if (map? (:body response))
        (-> response
            (update :body json/generate-string)
            (assoc-in [:headers "Content-Type"] "application/json; charset=utf-8"))
        response))))

;; ---------------------------------------------------------------------------
;; Error handler wrapper
;; ---------------------------------------------------------------------------

(defn wrap-error-handler
  "Top-level error boundary for unhandled exceptions.
   
   This is the outermost wrapper and catches any exceptions that escape
   the inner middleware layers, including routing errors."
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        (log/error e "Unhandled exception in handler")
        {:status 500
         :headers {"Content-Type" "application/json"}
         :body   {:error "Internal server error"}}))))
