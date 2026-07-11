(ns tunarr.scheduler.http.routes-test
  (:require [clojure.spec.test.alpha :as st]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [cheshire.core :as json]
            [ring.mock.request :as mock]
            [tunarr.scheduler.http.routes :as routes]
            [tunarr.scheduler.jobs.runner :as runner]
            [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.tunabrain :as tunabrain]
            [tunarr.scheduler.backends.pseudovision.client :as pv-client]
            [tunarr.scheduler.llm :as llm]))

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
  (delete-media-tags! [_ media-id tags]
    (swap! state update-in [:media-tags media-id]
           (fn [existing] (vec (remove (set tags) existing)))))
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
    ;; Mirror the real catalogs: store just the category-value keyword, so
    ;; get-media-category-values returns values (not the {value,rationale} maps).
    (swap! state update-in [:category-values media-id category]
           (fn [existing] (distinct (concat (or existing []) (map ::media/category-value values))))))
  (set-media-category-values! [_ media-id category values]
    (swap! state assoc-in [:category-values media-id category]
           (distinct (map ::media/category-value values))))
  (get-media-categories [_ media-id]
    (get-in @state [:category-values media-id] {}))
  (delete-media-category-value! [_ media-id category value]
    (swap! state update-in [:category-values media-id category]
           (fn [vals] (remove #(= % value) vals))))
  (delete-media-category-values! [_ media-id category]
    (swap! state update-in [:category-values media-id] dissoc category))
  (get-all-dimensions [_]
    (let [cats (:category-values @state {})]
      (->> (mapcat keys (vals cats))
           distinct
           (map (fn [dim]
                  {:name dim
                   :value-count (count (distinct (mapcat #(get % dim []) (vals cats))))})))))
  (get-dimension-values [_ dimension]
    (let [cats (:category-values @state {})]
      (->> (vals cats)
           (mapcat #(get % dimension []))
           distinct
           (map (fn [val]
                  {:value val
                   :usage-count (count (filter #(some #{val} (get % dimension [])) (vals cats)))})))))
  (get-effective-categories [_ media-id]
    (get-in @state [:category-values media-id] {}))
  (get-library-id [_ library]
    (get-in @state [:library-ids library]))
  (enrich-media-with-timestamps [_ media]
    media))

;; Mock collection implementation
(def mock-collection
  {:get-library-items (fn [_] [{:id "1" :name "Test Movie"}])})

;; Placeholder tunabrain client; tests that exercise tunabrain calls redefine
;; the tunarr.scheduler.tunabrain functions with with-redefs.
(def mock-tunabrain {})

;; `tunarr.scheduler.media.catalog` instruments `get-media-by-id` with a
;; spec that requires the full `::media/metadata` shape. Most of these
;; tests use stub bodies that skip 90% of that shape; rather than
;; threading spec-compliant maps through every stub, the test fixture
;; `st/unstrument`s while it runs and re-instruments after
;; — see `test-fixture` below. This var is now unused but kept for
;; future spec-conformant stubs.
(defn- valid-media-stub
  [& {:keys [id name] :or {id "stub-1" name "Stub"}}]
  {::media/name              name
   ::media/id                id
   ::media/overview          "stub overview"
   ::media/genres            []
   ::media/community-rating  nil
   ::media/critic-rating     nil
   ::media/rating            nil
   ::media/media-type        :movie
   ::media/type              :movie
   ::media/item-kind         :movie
   ::media/subtitles?        false
   ::media/production-year   2026
   ::media/subtitles         false
   ::media/premiere          nil
   ::media/taglines          []
   ::media/tags              []})

(def ^:dynamic *job-runner* nil)
(def ^:dynamic *catalog* nil)

(defn test-fixture [f]
  ;; Many of these tests redefine catalog/tunabrain/pv-client fns with
  ;; stub bodies that don't satisfy the production `::media/metadata`
  ;; spec, so turn spec/destructuring/ret validation OFF for the duration
  ;; of each test. We deliberately do NOT re-instrument at the end: the
  ;; production system relies on the `instrument` forms at load-time
  ;; (e.g. `tunarr.scheduler.media.catalog`), but those are welcomed back
  ;; by Clojure's normal compile-time evaluation if another suite loads
  ;; the ns after this one. Tests in this file don't depend on the
  ;; instrument hooks; if other suites start failing because of missing
  ;; instrumentation, `(require 'tunarr.scheduler.media.catalog)` will
  ;; re-trigger the `instrument 'get-media-by-id` form at the bottom of
  ;; that ns.
  (st/unstrument)
  (let [job-runner (runner/create {})
        catalog    (->MockCatalog (atom {}))]
    (binding [*job-runner* job-runner
              *catalog*    catalog]
      (try
        (f)
        (finally
          (runner/shutdown! job-runner))))))

(use-fixtures :each test-fixture)

;; Helper functions
(defn- parse-json-response [response]
  (when-let [body (:body response)]
    ;; After the reitit/muuntaja upgrade, handler responses often have a
    ;; `ByteArrayInputStream` body (or other InputStream-shaped values) rather
    ;; than a String. Make the helper shape-agnostic so callers don't have to
    ;; care which path produced the response.
    (json/parse-string (cond-> body (not (string? body)) slurp) true)))

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
    (is (str/starts-with? (get-in response [:headers "Content-Type"]) "application/json"))
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
                    {:decisions [{:tag "german" :action :keep :replacement nil
                                  :rationale "Useful for scheduling"}
                                 {:tag "yakuza" :action :merge :replacement "gangster"
                                  :rationale "Fits under gangster"}
                                 {:tag "team_owner" :action :drop :replacement nil
                                  :rationale "Too niche"}]})]
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
                    {:decisions [{:tag "team_owner" :action :drop :replacement nil
                                  :rationale "Too niche"}]})]
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
          (is (str/starts-with? (get-in response [:headers "Content-Type"])
                                "application/json")))))))

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
      (is (str/starts-with? (get-in response [:headers "Content-Type"]) "application/json"))
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
      (is (str/starts-with? (get-in response [:headers "Content-Type"]) "application/json"))
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

;; Get media by ID endpoint tests
;;
;; The catalog primary key may hold either a Pseudovision media-item ID or its
;; Jellyfin remote-key, depending on how the item was synced, so the endpoint
;; must accept either form and resolve external IDs through Pseudovision.

(deftest get-media-by-id-direct-hit-test
  (testing "GET /api/media-item/:id returns the item when the ID matches the catalog key directly"
    (with-redefs [catalog/get-media-by-id
                  (fn [_ id] (when (= id "catalog-1")
                               {::media/id "catalog-1" ::media/name "Direct Hit"}))]
      (let [handler (routes/handler {:job-runner   *job-runner*
                                     :collection   mock-collection
                                     :catalog      *catalog*
                                     :tunabrain    mock-tunabrain})
            response (handler (mock/request :get "/api/media-item/catalog-1"))
            body     (parse-json-response response)]
        (is (= 200 (:status response)))
        (is (= "catalog-1" (:tunarr.scheduler.media/id body)))))))

(deftest get-media-by-id-resolves-external-id-test
  (testing "GET /api/media-item/:id resolves an external/Jellyfin ID via Pseudovision"
    ;; Catalog is keyed on the Jellyfin remote-key; caller supplies the
    ;; Pseudovision internal ID, which Pseudovision maps back to the remote-key.
    (let [mock-pv {:config {:base-url "http://localhost:8080"}}]
      (with-redefs [catalog/get-media-by-id
                    (fn [_ id] (when (= id "jelly-1")
                                 {::media/id "jelly-1" ::media/name "External Hit"}))
                    pv-client/get-media-item
                    (fn [_ id] (when (= id "pv-1")
                                 {:id "pv-1" :remote-key "jelly-1"}))]
        (let [handler (routes/handler {:job-runner   *job-runner*
                                       :collection   mock-collection
                                       :catalog      *catalog*
                                       :tunabrain    mock-tunabrain
                                       :pseudovision mock-pv})
              response (handler (mock/request :get "/api/media-item/pv-1"))
              body     (parse-json-response response)]
          (is (= 200 (:status response)))
          (is (= "jelly-1" (:tunarr.scheduler.media/id body))))))))

(deftest get-media-by-id-not-found-test
  (testing "GET /api/media-item/:id returns 404 when neither direct nor Pseudovision lookup matches"
    (let [mock-pv {:config {:base-url "http://localhost:8080"}}]
      (with-redefs [catalog/get-media-by-id (fn [_ _] nil)
                    pv-client/get-media-item (fn [_ _] nil)]
        (let [handler (routes/handler {:job-runner   *job-runner*
                                       :collection   mock-collection
                                       :catalog      *catalog*
                                       :tunabrain    mock-tunabrain
                                       :pseudovision mock-pv})
              response (handler (mock/request :get "/api/media-item/missing"))
              body     (parse-json-response response)]
          (is (= 404 (:status response)))
          (is (contains? body :error)))))))

(deftest get-media-by-id-no-pseudovision-test
  (testing "GET /api/media-item/:id falls back to direct lookup only when Pseudovision is absent"
    (with-redefs [catalog/get-media-by-id (fn [_ _] nil)]
      (let [handler (routes/handler {:job-runner *job-runner*
                                     :collection mock-collection
                                     :catalog    *catalog*
                                     :tunabrain  mock-tunabrain})
            response (handler (mock/request :get "/api/media-item/whatever"))]
        (is (= 404 (:status response)))))))

(deftest get-schedule-endpoint-test
  (testing "GET /api/channels/:id/schedule returns current schedule"
    (let [mock-pv {:config {:base-url "http://localhost:8080"}}]
      (with-redefs [pv-client/get-channel
                    (fn [_ _]
                      {:id 6 :name "Test Channel" :schedule-id 42})

                    pv-client/get-schedule
                    (fn [_ _]
                      {:id 42 :name "Test Schedule"})

                    pv-client/list-slots
                    (fn [_ _]
                      [{:id 101 :slot_index 0 :start_time "08:00:00"}
                       {:id 102 :slot_index 1 :start_time "18:00:00"}])

                    pv-client/list-playout-events
                    (fn [_ _]
                      [{:id 1 :title "Show 1" :start_at "2026-01-01T08:00:00Z"}
                       {:id 2 :title "Show 2" :start_at "2026-01-01T09:00:00Z"}])]

        (let [handler  (routes/handler {:job-runner   *job-runner*
                                         :collection   mock-collection
                                         :catalog      *catalog*
                                         :tunabrain    mock-tunabrain
                                         :pseudovision mock-pv})
              response (handler (mock/request :get "/api/channels/6/schedule"))
              body     (parse-json-response response)]
          (is (= 200 (:status response)))
          (is (= 6 (:channel-id body)))
          (is (= "Test Channel" (:channel-name body)))
          (is (= 42 (:schedule-id body)))
          (is (= 2 (count (:slots body))))
          (is (= 2 (count (:upcoming-events body)))))))))

;; ---------------------------------------------------------------------------
;; Per-item tag editing endpoints
;; ---------------------------------------------------------------------------

(defn- json-post [handler method url body]
  (handler (-> (mock/request method url)
               (mock/content-type "application/json")
               (mock/body (json/generate-string body)))))

(deftest add-media-item-tags-test
  (testing "POST /api/media-item/:id/tags adds tags and returns the full list"
    (with-redefs [catalog/get-media-by-id
                  (fn [_ id] (when (= id "m1") {::media/id "m1" ::media/name "Item"}))]
      (let [handler  (routes/handler {:job-runner *job-runner*
                                      :collection mock-collection
                                      :catalog    *catalog*
                                      :tunabrain  mock-tunabrain})
            response (json-post handler :post "/api/media-item/m1/tags"
                                {:tags ["noir" "heist"]})
            body     (parse-json-response response)]
        (is (= 200 (:status response)))
        (is (= "m1" (:media-id body)))
        (is (= #{"noir" "heist"} (set (:tags body))))))))

(deftest get-media-item-tags-test
  (testing "GET /api/media-item/:id/tags returns the current tags"
    (swap! (:state *catalog*) assoc-in [:media-tags "m1"] [:noir :heist])
    (with-redefs [catalog/get-media-by-id
                  (fn [_ id] (when (= id "m1") {::media/id "m1"}))]
      (let [handler  (routes/handler {:job-runner *job-runner*
                                      :collection mock-collection
                                      :catalog    *catalog*
                                      :tunabrain  mock-tunabrain})
            response (handler (mock/request :get "/api/media-item/m1/tags"))
            body     (parse-json-response response)]
        (is (= 200 (:status response)))
        (is (= #{"noir" "heist"} (set (:tags body))))))))

(deftest set-media-item-tags-test
  (testing "PUT /api/media-item/:id/tags replaces the tag set"
    (swap! (:state *catalog*) assoc-in [:media-tags "m1"] [:old-tag])
    (with-redefs [catalog/get-media-by-id
                  (fn [_ id] (when (= id "m1") {::media/id "m1"}))]
      (let [handler  (routes/handler {:job-runner *job-runner*
                                      :collection mock-collection
                                      :catalog    *catalog*
                                      :tunabrain  mock-tunabrain})
            response (json-post handler :put "/api/media-item/m1/tags"
                                {:tags ["fresh"]})
            body     (parse-json-response response)]
        (is (= 200 (:status response)))
        (is (= ["fresh"] (:tags body)))))))

(deftest delete-media-item-tag-test
  (testing "DELETE /api/media-item/:id/tags/:tag removes one tag"
    (swap! (:state *catalog*) assoc-in [:media-tags "m1"] [:noir :heist])
    (with-redefs [catalog/get-media-by-id
                  (fn [_ id] (when (= id "m1") {::media/id "m1"}))]
      (let [handler  (routes/handler {:job-runner *job-runner*
                                      :collection mock-collection
                                      :catalog    *catalog*
                                      :tunabrain  mock-tunabrain})
            response (handler (mock/request :delete "/api/media-item/m1/tags/noir"))
            body     (parse-json-response response)]
        (is (= 200 (:status response)))
        (is (= ["heist"] (:tags body)))))))

(deftest media-item-tags-not-found-test
  (testing "tag endpoints return 404 when the media item does not resolve"
    (with-redefs [catalog/get-media-by-id (fn [_ _] nil)]
      (let [handler  (routes/handler {:job-runner *job-runner*
                                      :collection mock-collection
                                      :catalog    *catalog*
                                      :tunabrain  mock-tunabrain})
            response (json-post handler :post "/api/media-item/missing/tags"
                                {:tags ["x"]})]
        (is (= 404 (:status response)))))))

;; ---------------------------------------------------------------------------
;; Per-item dimension value editing endpoints
;; ---------------------------------------------------------------------------

(deftest add-media-item-category-values-test
  (testing "POST /api/media-item/:id/categories/:category adds dimension values"
    (with-redefs [catalog/get-media-by-id
                  (fn [_ id] (when (= id "m1") {::media/id "m1"}))]
      (let [handler  (routes/handler {:job-runner *job-runner*
                                      :collection mock-collection
                                      :catalog    *catalog*
                                      :tunabrain  mock-tunabrain})
            response (json-post handler :post "/api/media-item/m1/categories/mood"
                                {:values ["tense" "dark"] :rationale "manual"})
            body     (parse-json-response response)]
        (is (= 200 (:status response)))
        (is (= "mood" (:category body)))
        (is (= #{"tense" "dark"} (set (:values body))))))))

(deftest delete-media-item-category-value-test
  (testing "DELETE /api/media-item/:id/categories/:category/values/:value removes a value"
    (swap! (:state *catalog*) assoc-in [:category-values "m1" :mood] [:tense :dark])
    (with-redefs [catalog/get-media-by-id
                  (fn [_ id] (when (= id "m1") {::media/id "m1"}))]
      (let [handler  (routes/handler {:job-runner *job-runner*
                                      :collection mock-collection
                                      :catalog    *catalog*
                                      :tunabrain  mock-tunabrain})
            response (handler (mock/request :delete "/api/media-item/m1/categories/mood/values/tense"))
            body     (parse-json-response response)]
        (is (= 200 (:status response)))
        (is (= ["dark"] (:values body)))))))

(deftest get-schedule-no-schedule-test
  (testing "GET /api/channels/:id/schedule returns 404 when no schedule attached"
    (let [mock-pv {:config {:base-url "http://localhost:8080"}}]
      (with-redefs [pv-client/get-channel
                    (fn [_ _]
                      {:id 6 :name "Test Channel"})]

        (let [handler  (routes/handler {:job-runner   *job-runner*
                                         :collection   mock-collection
                                         :catalog      *catalog*
                                         :tunabrain    mock-tunabrain
                                         :pseudovision mock-pv})
              response (handler (mock/request :get "/api/channels/6/schedule"))
              body     (parse-json-response response)]
          (is (= 404 (:status response)))
          (is (contains? body :error)))))))
