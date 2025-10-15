(ns tunarr.scheduler.http.routes-test
  (:require [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]
            [ring.mock.request :as mock]
            [tunarr.scheduler.http.routes :as routes]))

(def base-deps
  {:media {:state (atom [])}
   :scheduler {:config {:time-zone "UTC"}}
   :llm {:type :mock}
   :tts {:type :mock}
   :persistence {:type :memory :state (atom nil)}
   :bumpers {:llm {:type :mock} :tts {:type :mock}}})

(deftest health-endpoint-test
  (let [handler (routes/handler base-deps)
        response (handler (mock/request :get "/healthz"))]
    (is (= 200 (:status response)))
    (is (= "application/json" (get-in response [:headers "Content-Type"])))
    (is (= {:status "ok"}
           (json/parse-string (:body response) true)))))

#_(deftest bumper-endpoint-test
  (let [handler (routes/handler base-deps)
        request (-> (mock/request :post "/api/bumpers/up-next"
                                  (json/generate-string {:channel "Channel 1"
                                                         :upcoming "Movie"}))
                    (mock/content-type "application/json"))
        response (handler request)]
    (is (= 202 (:status response)))))
