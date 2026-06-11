(ns tunarr.scheduler.http.routes-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cheshire.core :as json]
            [ring.mock.request :as mock]
            [tunarr.scheduler.http.routes :as routes]
            [tunarr.scheduler.jobs.runner :as runner]
            [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.tunabrain :as tunabrain]
            [tunarr.scheduler.backends.pseudovision.client :as pv-client]))

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
  (batch-rename-tags! [_ tag-pairs]
    (swap! state update :tags
           (fn [tags]
             (map (fn [t]
                    (or (some (fn [[old new]]
                                (when (= (name t) (name old)) (keyword new)))
                              tag-pairs)
                        t))
                  tags))))
  (update-process-timestamp! [_ media-id process]
    (swap! state update-in [:process-timestamps media-id] conj process))
  (close-catalog! [_] nil)
  (get-media-category-values [_ media-id category]
    (get-in @state [:category-values media-id category] []))
  (add-media-category-value! [_ media-id category value rationale]
    (swap! state update-in [:category-values media-id category]
           (fnil conj []) {::media/category-value value ::media/rationale rationale}))
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

;; Placeholder tunabrain client; tests that exercise tunabrain calls redefine
;; the tunarr.scheduler.tunabrain functions with with-redefs.
(def mock-tunabrain {})

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
(deftest audit-tags-endpoint-submits-job-test
  (testing "POST /api/media/tags/audit submits a tag audit job"
    (with-redefs [tunabrain/request-tag-audit!
                  (fn [_ _tags] {:recommended-for-removal []})]
      (let [handler (routes/handler {:job-runner *job-runner*
                                     :collection mock-collection
                                     :catalog *catalog*
                                     :tunabrain mock-tunabrain})
            response (handler (mock/request :post "/api/media/tags/audit"))]
        (is (= 202 (:status response)))
        (let [body (parse-json-response response)]
          (is (contains? body :job))
          (is (= :media/tag-audit (get-in body [:job :type])))
          (let [job-info (await-job *job-runner* (get-in body [:job :id]) 5000)]
            (is (= :succeeded (:status job-info)))
            (is (contains? (:result job-info) :tags-audited))
            (is (contains? (:result job-info) :tags-removed))
            (is (contains? (:result job-info) :removed))))))))

(deftest audit-tags-removes-inappropriate-tags-test
  (testing "audit job removes tags recommended for removal"
    (swap! (:state *catalog*) assoc :tags [:action :comedy :inappropriate])
    (with-redefs [tunabrain/request-tag-audit!
                  (fn [_ _tags]
                    {:recommended-for-removal
                     [{:tag "inappropriate" :reason "Violates content policy"}]})]
      (let [handler (routes/handler {:job-runner *job-runner*
                                     :collection mock-collection
                                     :catalog *catalog*
                                     :tunabrain mock-tunabrain})
            response (handler (mock/request :post "/api/media/tags/audit"))
            body (parse-json-response response)
            job-info (await-job *job-runner* (get-in body [:job :id]) 5000)
            result (:result job-info)]
        (is (= :succeeded (:status job-info)))
        (is (= 1 (:tags-removed result)))
        (is (= 1 (count (:removed result))))
        (is (= "inappropriate" (get-in result [:removed 0 :tag])))
        ;; Verify tag was actually deleted
        (let [remaining-tags (catalog/get-tags *catalog*)]
          (is (not (contains? (set remaining-tags) :inappropriate))))))))

(deftest audit-tags-dry-run-test
  (testing "audit job with ?dry-run=true reports without deleting"
    (swap! (:state *catalog*) assoc :tags [:action :comedy :inappropriate])
    (with-redefs [tunabrain/request-tag-audit!
                  (fn [_ _tags]
                    {:recommended-for-removal
                     [{:tag "inappropriate" :reason "Violates content policy"}]})]
      (let [handler (routes/handler {:job-runner *job-runner*
                                     :collection mock-collection
                                     :catalog *catalog*
                                     :tunabrain mock-tunabrain})
            response (handler (mock/request :post "/api/media/tags/audit?dry-run=true"))
            body (parse-json-response response)
            job-info (await-job *job-runner* (get-in body [:job :id]) 5000)
            result (:result job-info)]
        (is (= :succeeded (:status job-info)))
        (is (= 0 (:tags-removed result)))
        (is (= 1 (count (:removed result))))
        (is (true? (:dry-run result)))
        ;; Tag must still be present
        (is (contains? (set (catalog/get-tags *catalog*)) :inappropriate))))))

;; Tag triage endpoint tests
(deftest triage-tags-endpoint-applies-decisions-test
  (testing "POST /api/media/tags/triage applies keep/remove/rename decisions"
    (swap! (:state *catalog*) assoc
           :tags [:german :yakuza :team_owner]
           :tag-samples [{:tag "german" :usage_count 40 :example_titles ["Das Boot"]}
                         {:tag "yakuza" :usage_count 7 :example_titles ["Outrage"]}
                         {:tag "team_owner" :usage_count 1 :example_titles ["Major League"]}])
    (with-redefs [tunabrain/request-tag-triage!
                  (fn [_ samples & _]
                    (is (= 3 (count samples)))
                    {:decisions [{:tag "german" :action :keep :reason "Useful for scheduling"}
                                 {:tag "yakuza" :action :merge :merge_into "gangster"
                                  :reason "Fits under gangster"}
                                 {:tag "team_owner" :action :remove :reason "Too niche"}]})]
      (let [handler (routes/handler {:job-runner *job-runner*
                                     :collection mock-collection
                                     :catalog *catalog*
                                     :tunabrain mock-tunabrain})
            response (handler (mock/request :post "/api/media/tags/triage?target-limit=50"))
            body (parse-json-response response)]
        (is (= 202 (:status response)))
        (is (= :media/tag-triage (get-in body [:job :type])))
        (let [job-info (await-job *job-runner* (get-in body [:job :id]) 5000)
              result (:result job-info)]
          (is (= :succeeded (:status job-info)))
          (is (= 3 (:tags-triaged result)))
          (is (= 1 (:kept result)))
          (is (= 1 (:deleted result)))
          (is (= 1 (:renamed result)))
          (let [remaining (set (catalog/get-tags *catalog*))]
            (is (contains? remaining :german))
            (is (contains? remaining :gangster))
            (is (not (contains? remaining :yakuza)))
            (is (not (contains? remaining :team_owner)))))))))

(deftest triage-tags-dry-run-test
  (testing "triage job with ?dry-run=true reports decisions without applying"
    (swap! (:state *catalog*) assoc
           :tags [:team_owner]
           :tag-samples [{:tag "team_owner" :usage_count 1 :example_titles ["Major League"]}])
    (with-redefs [tunabrain/request-tag-triage!
                  (fn [_ _samples & _]
                    {:decisions [{:tag "team_owner" :action :remove :reason "Too niche"}]})]
      (let [handler (routes/handler {:job-runner *job-runner*
                                     :collection mock-collection
                                     :catalog *catalog*
                                     :tunabrain mock-tunabrain})
            response (handler (mock/request :post "/api/media/tags/triage?dry-run=true"))
            body (parse-json-response response)
            job-info (await-job *job-runner* (get-in body [:job :id]) 5000)
            result (:result job-info)]
        (is (= :succeeded (:status job-info)))
        (is (true? (:dry-run result)))
        (is (= 1 (count (:decisions result))))
        (is (contains? (set (catalog/get-tags *catalog*)) :team_owner))))))

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
  (testing "nonexistent endpoints return 404 with JSON error"
    (let [handler (routes/handler {:job-runner *job-runner*
                                   :collection mock-collection
                                   :catalog *catalog*
                                   :tunabrain mock-tunabrain})
          response (handler (mock/request :get "/api/nonexistent"))
          body (parse-json-response response)]
      (is (= 404 (:status response)))
      (is (= "application/json" (get-in response [:headers "Content-Type"])))
      (is (= "Not found" (:error body))))))

;; 405 Method Not Allowed tests
(deftest method-not-allowed-test
  (testing "wrong HTTP method returns 405 with JSON error"
    (let [handler (routes/handler {:job-runner *job-runner*
                                   :collection mock-collection
                                   :catalog *catalog*
                                   :tunabrain mock-tunabrain})
          response (handler (mock/request :delete "/api/jobs"))
          body (parse-json-response response)]
      (is (= 405 (:status response)))
      (is (= "application/json" (get-in response [:headers "Content-Type"])))
      (is (= "Method not allowed" (:error body))))))

;; List libraries endpoint tests
(deftest list-libraries-from-pseudovision-test
  (testing "GET /api/media/libraries returns libraries fetched from Pseudovision"
    (let [mock-libraries [{:id 1 :name "Movies" :kind "movies"}
                          {:id 2 :name "TV Shows" :kind "shows"}]
          mock-pv {:config {:base-url "http://localhost:8080"}}]
      (with-redefs [pv-client/list-all-libraries (fn [_] mock-libraries)]
        (let [handler (routes/handler {:job-runner *job-runner*
                                       :collection mock-collection
                                       :catalog *catalog*
                                       :tunabrain mock-tunabrain
                                       :pseudovision mock-pv})
              response (handler (mock/request :get "/api/media/libraries"))
              body (parse-json-response response)]
          (is (= 200 (:status response)))
          (is (= 2 (count (:libraries body))))
          (is (= "Movies" (:name (first (:libraries body))))))))))

(deftest list-libraries-no-pseudovision-test
  (testing "GET /api/media/libraries returns empty list when Pseudovision is not configured"
    (let [handler (routes/handler {:job-runner *job-runner*
                                   :collection mock-collection
                                   :catalog *catalog*
                                   :tunabrain mock-tunabrain})
          response (handler (mock/request :get "/api/media/libraries"))
          body (parse-json-response response)]
      (is (= 200 (:status response)))
      (is (= [] (:libraries body))))))

;; Sync libraries endpoint tests
(deftest sync-libraries-registers-in-catalog-test
  (testing "POST /api/media/sync-libraries registers PV libraries in catalog"
    (let [mock-libraries [{:id 1 :name "Movies" :kind "movies"}
                          {:id 2 :name "TV Shows" :kind "shows"}]
          mock-pv {:config {:base-url "http://localhost:8080"}}]
      (with-redefs [pv-client/list-all-libraries (fn [_] mock-libraries)]
        (let [handler (routes/handler {:job-runner   *job-runner*
                                       :collection   mock-collection
                                       :catalog      *catalog*
                                       :tunabrain    mock-tunabrain
                                       :pseudovision mock-pv})
              response (handler (mock/request :post "/api/media/sync-libraries"))
              body     (parse-json-response response)]
          (is (= 200 (:status response)))
          (is (= 2 (count (:libraries body))))
          (is (= "Movies" (:name (first (:libraries body)))))
          (let [registered (get @(:state *catalog*) :libraries)]
            (is (= 1 (:movies registered)))
            (is (= 2 (:shows registered)))))))))

(deftest sync-libraries-no-pseudovision-test
  (testing "POST /api/media/sync-libraries returns 400 when Pseudovision is not configured"
    (let [handler (routes/handler {:job-runner *job-runner*
                                   :collection mock-collection
                                   :catalog    *catalog*
                                   :tunabrain  mock-tunabrain})
          response (handler (mock/request :post "/api/media/sync-libraries"))
          body     (parse-json-response response)]
      (is (= 400 (:status response)))
      (is (contains? body :error)))))

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
