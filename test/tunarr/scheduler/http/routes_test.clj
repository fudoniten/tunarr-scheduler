(ns tunarr.scheduler.http.routes-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cheshire.core :as json]
            [ring.mock.request :as mock]
            [tunarr.scheduler.http.routes :as routes]
            [tunarr.scheduler.jobs.runner :as runner]
            [tunarr.scheduler.media.catalog :as catalog]))

;; Mock catalog implementation for testing
(defrecord MockCatalog [state]
  catalog/Catalog
  (add-media! [_ media]
    (swap! state update :media conj media))
  (add-media-batch! [_ media-items]
    (swap! state update :media concat media-items))
  (get-media [_]
    (get @state :media []))
  (get-media-by-id [_ media-id]
    (filter #(= (:id %) media-id) (get @state :media [])))
  (get-media-by-library-id [_ library-id]
    (filter #(= (:library-id %) library-id) (get @state :media [])))
  (get-media-by-library [_ library]
    (filter #(= (:library %) library) (get @state :media [])))
  (get-tags [_]
    (get @state :tags [:action :comedy :drama]))
  (get-media-tags [_ media-id]
    (get-in @state [:media-tags media-id] []))
  (add-media-tags! [_ media-id tags]
    (swap! state update-in [:media-tags media-id] (fnil concat []) tags))
  (set-media-tags! [_ media-id tags]
    (swap! state assoc-in [:media-tags media-id] tags))
  (update-channels! [_ channels]
    (swap! state assoc :channels channels))
  (update-libraries! [_ libraries]
    (swap! state assoc :libraries libraries))
  (add-media-channels! [_ media-id channels]
    (swap! state update-in [:media-channels media-id] (fnil concat []) channels))
  (add-media-genres! [_ media-id genres]
    (swap! state update-in [:media-genres media-id] (fnil concat []) genres))
  (add-media-taglines! [_ media-id taglines]
    (swap! state update-in [:media-taglines media-id] (fnil concat []) taglines))
  (get-media-by-channel [_ channel]
    (filter #(contains? (set (get-in @state [:media-channels (:id %)])) channel)
            (get @state :media [])))
  (get-media-by-tag [_ tag]
    (filter #(contains? (set (get-in @state [:media-tags (:id %)])) tag)
            (get @state :media [])))
  (get-media-by-genre [_ genre]
    (filter #(contains? (set (get-in @state [:media-genres (:id %)])) genre)
            (get @state :media [])))
  (get-media-process-timestamps [_ media]
    (get-in @state [:process-timestamps (:id media)] []))
  (get-tag-samples [_]
    (get @state :tag-samples []))
  (delete-tag! [_ tag]
    (swap! state update :tags (fn [tags] (remove #(= % tag) tags))))
  (rename-tag! [_ tag new-tag]
    (swap! state update :tags (fn [tags] (map #(if (= % tag) new-tag %) tags))))
  (update-process-timestamp! [_ media-id process]
    (swap! state update-in [:process-timestamps media-id] conj process))
  (close-catalog! [_] nil)
  (get-media-category-values [_ media-id category]
    (get-in @state [:category-values media-id category] []))
  (add-media-category-value! [_ media-id category value]
    (swap! state update-in [:category-values media-id category] (fnil conj []) value))
  (add-media-category-values! [_ media-id category values]
    (swap! state update-in [:category-values media-id category] (fnil concat []) values))
  (set-media-category-values! [_ media-id category values]
    (swap! state assoc-in [:category-values media-id category] values))
  (get-media-categories [_ media-id]
    (get-in @state [:category-values media-id] {}))
  (delete-media-category-value! [_ media-id category value]
    (swap! state update-in [:category-values media-id category]
           (fn [vals] (remove #(= % value) vals))))
  (delete-media-category-values! [_ media-id category]
    (swap! state update-in [:category-values media-id] dissoc category)))

;; Mock collection implementation
(def mock-collection
  {:get-library-items (fn [_] [{:id "1" :name "Test Movie"}])})

;; Mock tunabrain implementation
(def mock-tunabrain
  {:request-tag-audit! (fn [_ tags]
                        {:recommended-for-removal
                         [{:tag "inappropriate" :reason "Violates content policy"}]})})

(def ^:dynamic *job-runner* nil)
(def ^:dynamic *catalog* nil)

(defn test-fixture [f]
  (let [job-runner (runner/create {})
        catalog (->MockCatalog (atom {}))]
    (binding [*job-runner* job-runner
              *catalog* catalog]
      (try
        (f)
        (finally
          (runner/shutdown! job-runner))))))

(use-fixtures :each test-fixture)

;; Helper functions
(defn- parse-json-response [response]
  (when-let [body (:body response)]
    (json/parse-string body true)))

(defn- await-job [job-runner job-id timeout-ms]
  (loop [remaining timeout-ms]
    (let [info (runner/job-info job-runner job-id)]
      (cond
        (nil? info) nil
        (#{:succeeded :failed} (:status info)) info
        (pos? remaining) (do (Thread/sleep 50)
                            (recur (- remaining 50)))
        :else info))))

;; Health check tests
(deftest health-endpoint-test
  (let [handler (routes/handler {:job-runner *job-runner*
                                 :collection mock-collection
                                 :catalog *catalog*
                                 :tunabrain mock-tunabrain})
        response (handler (mock/request :get "/healthz"))]
    (is (= 200 (:status response)))
    (is (= "application/json" (get-in response [:headers "Content-Type"])))
    (is (= {:status "ok"}
           (parse-json-response response)))))

;; Media rescan endpoint tests
(deftest rescan-endpoint-submits-job-test
  (testing "POST /api/media/:library/rescan submits a rescan job"
    (let [handler (routes/handler {:job-runner *job-runner*
                                   :collection mock-collection
                                   :catalog *catalog*
                                   :tunabrain mock-tunabrain})
          response (handler (mock/request :post "/api/media/test-library/rescan"))]
      (is (= 202 (:status response)))
      (let [body (parse-json-response response)]
        (is (contains? body :job))
        (is (contains? (:job body) :id))
        (is (= :media/rescan (get-in body [:job :type])))))))

(deftest rescan-endpoint-job-has-library-metadata-test
  (testing "rescan job includes library metadata"
    (let [handler (routes/handler {:job-runner *job-runner*
                                   :collection mock-collection
                                   :catalog *catalog*
                                   :tunabrain mock-tunabrain})
          response (handler (mock/request :post "/api/media/movies/rescan"))
          body (parse-json-response response)
          job-id (get-in body [:job :id])]
      (let [job-info (runner/job-info *job-runner* job-id)]
        (is (= "movies" (get-in job-info [:metadata :library])))))))

;; Media retag endpoint tests
(deftest retag-endpoint-submits-job-test
  (testing "POST /api/media/:library/retag submits a retag job"
    (let [handler (routes/handler {:job-runner *job-runner*
                                   :collection mock-collection
                                   :catalog *catalog*
                                   :tunabrain mock-tunabrain})
          response (handler (mock/request :post "/api/media/test-library/retag"))]
      (is (= 202 (:status response)))
      (let [body (parse-json-response response)]
        (is (contains? body :job))
        (is (= :media/retag (get-in body [:job :type])))))))

(deftest retag-endpoint-job-has-library-metadata-test
  (testing "retag job includes library metadata"
    (let [handler (routes/handler {:job-runner *job-runner*
                                   :collection mock-collection
                                   :catalog *catalog*
                                   :tunabrain mock-tunabrain})
          response (handler (mock/request :post "/api/media/shows/retag"))
          body (parse-json-response response)
          job-id (get-in body [:job :id])]
      (let [job-info (runner/job-info *job-runner* job-id)]
        (is (= "shows" (get-in job-info [:metadata :library])))))))

;; Add taglines endpoint tests
(deftest add-taglines-endpoint-submits-job-test
  (testing "POST /api/media/:library/add-taglines submits a tagline job"
    (let [handler (routes/handler {:job-runner *job-runner*
                                   :collection mock-collection
                                   :catalog *catalog*
                                   :tunabrain mock-tunabrain})
          response (handler (mock/request :post "/api/media/test-library/add-taglines"))]
      (is (= 202 (:status response)))
      (let [body (parse-json-response response)]
        (is (contains? body :job))
        (is (= :media/taglines (get-in body [:job :type])))))))

(deftest add-taglines-endpoint-job-has-library-metadata-test
  (testing "tagline job includes library metadata"
    (let [handler (routes/handler {:job-runner *job-runner*
                                   :collection mock-collection
                                   :catalog *catalog*
                                   :tunabrain mock-tunabrain})
          response (handler (mock/request :post "/api/media/documentaries/add-taglines"))
          body (parse-json-response response)
          job-id (get-in body [:job :id])]
      (let [job-info (runner/job-info *job-runner* job-id)]
        (is (= "documentaries" (get-in job-info [:metadata :library])))))))

;; Tag audit endpoint tests
(deftest audit-tags-endpoint-test
  (testing "POST /api/media/tags/audit audits tags"
    (let [handler (routes/handler {:job-runner *job-runner*
                                   :collection mock-collection
                                   :catalog *catalog*
                                   :tunabrain mock-tunabrain})
          response (handler (mock/request :post "/api/media/tags/audit"))]
      (is (= 200 (:status response)))
      (let [body (parse-json-response response)]
        (is (contains? body :tags-audited))
        (is (contains? body :tags-removed))
        (is (contains? body :removed))))))

(deftest audit-tags-removes-inappropriate-tags-test
  (testing "audit endpoint removes tags recommended for removal"
    (swap! (:state *catalog*) assoc :tags [:action :comedy :inappropriate])
    (let [handler (routes/handler {:job-runner *job-runner*
                                   :collection mock-collection
                                   :catalog *catalog*
                                   :tunabrain mock-tunabrain})
          response (handler (mock/request :post "/api/media/tags/audit"))
          body (parse-json-response response)]
      (is (= 1 (:tags-removed body)))
      (is (= 1 (count (:removed body))))
      (is (= "inappropriate" (get-in body [:removed 0 :tag])))
      ;; Verify tag was actually deleted
      (let [remaining-tags (catalog/get-tags *catalog*)]
        (is (not (contains? (set remaining-tags) :inappropriate)))))))

;; Job listing endpoint tests
(deftest list-jobs-endpoint-test
  (testing "GET /api/jobs returns list of jobs"
    (let [handler (routes/handler {:job-runner *job-runner*
                                   :collection mock-collection
                                   :catalog *catalog*
                                   :tunabrain mock-tunabrain})]
      ;; Submit a couple of jobs first
      (handler (mock/request :post "/api/media/lib1/rescan"))
      (handler (mock/request :post "/api/media/lib2/retag"))

      (let [response (handler (mock/request :get "/api/jobs"))
            body (parse-json-response response)]
        (is (= 200 (:status response)))
        (is (contains? body :jobs))
        (is (>= (count (:jobs body)) 2))))))

(deftest list-jobs-returns-newest-first-test
  (testing "jobs are returned in newest-first order"
    (let [handler (routes/handler {:job-runner *job-runner*
                                   :collection mock-collection
                                   :catalog *catalog*
                                   :tunabrain mock-tunabrain})]
      ;; Submit jobs
      (let [first-response (handler (mock/request :post "/api/media/lib1/rescan"))
            first-job-id (get-in (parse-json-response first-response) [:job :id])]
        (Thread/sleep 10) ; Small delay to ensure different timestamps
        (let [second-response (handler (mock/request :post "/api/media/lib2/retag"))
              second-job-id (get-in (parse-json-response second-response) [:job :id])]

          (let [list-response (handler (mock/request :get "/api/jobs"))
                body (parse-json-response list-response)
                jobs (:jobs body)]
            (is (= second-job-id (:id (first jobs))))
            (is (= first-job-id (:id (second jobs))))))))))

;; Job info endpoint tests
(deftest get-job-info-endpoint-test
  (testing "GET /api/jobs/:job-id returns job information"
    (let [handler (routes/handler {:job-runner *job-runner*
                                   :collection mock-collection
                                   :catalog *catalog*
                                   :tunabrain mock-tunabrain})
          submit-response (handler (mock/request :post "/api/media/test-library/rescan"))
          job-id (get-in (parse-json-response submit-response) [:job :id])
          info-response (handler (mock/request :get (str "/api/jobs/" job-id)))
          body (parse-json-response info-response)]
      (is (= 200 (:status info-response)))
      (is (contains? body :job))
      (is (= job-id (get-in body [:job :id])))
      (is (= :media/rescan (get-in body [:job :type]))))))

(deftest get-job-info-not-found-test
  (testing "GET /api/jobs/:job-id returns 404 for nonexistent job"
    (let [handler (routes/handler {:job-runner *job-runner*
                                   :collection mock-collection
                                   :catalog *catalog*
                                   :tunabrain mock-tunabrain})
          response (handler (mock/request :get "/api/jobs/nonexistent-job-id"))
          body (parse-json-response response)]
      (is (= 404 (:status response)))
      (is (contains? body :error))
      (is (= "Job not found" (:error body))))))

;; Job status tracking tests
(deftest job-status-tracking-test
  (testing "job status can be tracked through completion"
    (let [handler (routes/handler {:job-runner *job-runner*
                                   :collection mock-collection
                                   :catalog *catalog*
                                   :tunabrain mock-tunabrain})
          submit-response (handler (mock/request :post "/api/media/test-library/rescan"))
          job-id (get-in (parse-json-response submit-response) [:job :id])]

      ;; Wait for job to complete
      (let [final-status (await-job *job-runner* job-id 5000)]
        (is final-status)
        (is (contains? #{:succeeded :failed} (:status final-status)))))))

;; Content-Type header tests
(deftest response-content-type-test
  (testing "all endpoints return JSON content type"
    (let [handler (routes/handler {:job-runner *job-runner*
                                   :collection mock-collection
                                   :catalog *catalog*
                                   :tunabrain mock-tunabrain})]
      (doseq [endpoint ["/healthz"
                       "/api/media/test-library/rescan"
                       "/api/media/test-library/retag"
                       "/api/media/test-library/add-taglines"
                       "/api/media/tags/audit"
                       "/api/jobs"]]
        (let [method (if (clojure.string/starts-with? endpoint "/healthz") :get :post)
              response (handler (mock/request method endpoint))]
          (is (= "application/json" (get-in response [:headers "Content-Type"]))))))))

;; 404 Not Found tests
(deftest not-found-endpoint-test
  (testing "nonexistent endpoints return 404"
    (let [handler (routes/handler {:job-runner *job-runner*
                                   :collection mock-collection
                                   :catalog *catalog*
                                   :tunabrain mock-tunabrain})
          response (handler (mock/request :get "/api/nonexistent"))]
      (is (= 404 (:status response))))))

;; Edge case: empty library name
(deftest empty-library-name-test
  (testing "endpoints handle empty library names appropriately"
    (let [handler (routes/handler {:job-runner *job-runner*
                                   :collection mock-collection
                                   :catalog *catalog*
                                   :tunabrain mock-tunabrain})
          response (handler (mock/request :post "/api/media//rescan"))]
      ;; This should either be a 404 (route not matched) or handled gracefully
      (is (or (= 404 (:status response))
              (= 400 (:status response)))))))
