(ns tunarr.scheduler.http.api.media-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [tunarr.scheduler.http.api.media :as media]
            [tunarr.scheduler.http.schemas :as s]
            [tunarr.scheduler.media :as media-ns]
            [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.media.pseudovision-media-sync :as pv-media-sync]
            [tunarr.scheduler.backends.pseudovision.client :as pv-client]
            [tunarr.scheduler.curation.core :as curate]
            [tunarr.scheduler.jobs.runner :as runner]))

;; ---------------------------------------------------------------------------
;; Minimal catalog double for the per-item context handlers. get-media-by-id
;; returns a media map (matching the SqlCatalog contract resolve-media-by-id
;; relies on), and the context methods are backed by a plain atom.
;; ---------------------------------------------------------------------------

(defrecord CtxCatalog [media-index context]
  catalog/Catalog
  (get-media-by-id [_ id] (get @media-index id))
  (get-media-context [_ id] (get @context id))
  (set-media-context! [_ id ctx] (swap! context assoc id ctx) nil)
  (delete-media-context! [_ id] (swap! context dissoc id) nil))

(defn- ctx-with [& {:keys [context]}]
  (let [catalog (->CtxCatalog (atom {"m1" {::media-ns/id "m1"}})
                              (atom (or context {})))]
    {:catalog catalog :pseudovision nil}))

(defn- req [& {:keys [body]}]
  {:parameters (cond-> {:path {:media-id "m1"}}
                 body (assoc :body body))})

(deftest add-link-handler-test
  (testing "adding a link stores it and marks the context operator-edited"
    (let [ctx  (ctx-with)
          resp ((media/add-media-item-context-link-handler ctx)
                (req :body {:link "https://en.wikipedia.org/wiki/Juice_(1992_film)"}))]
      (is (= 200 (:status resp)))
      (is (= ["https://en.wikipedia.org/wiki/Juice_(1992_film)"]
             (get-in resp [:body :context :links])))
      (is (true? (get-in resp [:body :context :operator-edited]))))))

(deftest add-link-dedupes-test
  (testing "adding the same link twice does not duplicate it"
    (let [ctx (ctx-with)
          h   (media/add-media-item-context-link-handler ctx)]
      (h (req :body {:link "a"}))
      (let [resp (h (req :body {:link "a"}))]
        (is (= ["a"] (get-in resp [:body :context :links])))))))

(deftest remove-link-handler-test
  (testing "removing a link drops just that link"
    (let [ctx (ctx-with :context {"m1" {:links ["a" "b"] :operator-edited true}})
          resp ((media/delete-media-item-context-link-handler ctx)
                (req :body {:link "a"}))]
      (is (= ["b"] (get-in resp [:body :context :links]))))))

(deftest set-and-clear-text-handler-test
  (testing "text can be set and cleared without disturbing links"
    (let [ctx (ctx-with :context {"m1" {:links ["a"]}})
          set-resp ((media/set-media-item-context-text-handler ctx)
                    (req :body {:text "Violent Harlem crime drama."}))]
      (is (= "Violent Harlem crime drama." (get-in set-resp [:body :context :text])))
      (is (= ["a"] (get-in set-resp [:body :context :links])) "links preserved")
      (let [clear-resp ((media/delete-media-item-context-text-handler ctx) (req))]
        (is (nil? (get-in clear-resp [:body :context :text])))
        (is (= ["a"] (get-in clear-resp [:body :context :links])) "links still preserved")))))

(deftest set-and-clear-summary-handler-test
  (testing "summary can be pinned and cleared"
    (let [ctx (ctx-with)
          set-resp ((media/set-media-item-context-summary-handler ctx)
                    (req :body {:summary "Juice (1992) is a crime drama."}))]
      (is (= "Juice (1992) is a crime drama." (get-in set-resp [:body :context :summary])))
      (let [clear-resp ((media/delete-media-item-context-summary-handler ctx) (req))]
        (is (nil? (get-in clear-resp [:body :context :summary])))))))

(deftest put-whole-context-handler-test
  (testing "PUT replaces the whole context and marks it operator-edited"
    (let [ctx (ctx-with :context {"m1" {:links ["old"] :summary "old"}})
          resp ((media/set-media-item-context-handler ctx)
                (req :body {:summary "new" :links ["x"]}))]
      (is (= "new" (get-in resp [:body :context :summary])))
      (is (= ["x"] (get-in resp [:body :context :links])))
      (is (nil? (get-in resp [:body :context :text])))
      (is (true? (get-in resp [:body :context :operator-edited]))))))

(deftest delete-whole-context-handler-test
  (testing "DELETE removes the stored context entirely"
    (let [ctx (ctx-with :context {"m1" {:summary "x"}})
          resp ((media/delete-media-item-context-handler ctx) (req))]
      (is (= 200 (:status resp)))
      (is (nil? (get-in resp [:body :context])))
      (is (nil? (catalog/get-media-context (:catalog ctx) "m1"))))))

(deftest get-context-handler-test
  (testing "GET returns the stored context"
    (let [ctx (ctx-with :context {"m1" {:summary "grounded" :links [] :operator-edited false}})
          resp ((media/get-media-item-context-handler ctx) (req))]
      (is (= "grounded" (get-in resp [:body :context :summary]))))))

(deftest context-handler-404-test
  (testing "an unknown media id yields 404"
    (let [ctx (ctx-with)
          resp ((media/get-media-item-context-handler ctx)
                {:parameters {:path {:media-id "nope"}}})]
      (is (= 404 (:status resp))))))

;; ---------------------------------------------------------------------------
;; curate-all — the nightly curation pass. Verifies it operates on exactly the
;; configured Jellyfin libraries and never the grout-content library (Grout owns
;; its own tags, so re-tagging it here would fight Grout).
;; ---------------------------------------------------------------------------

(defn- await-terminal [r job-id]
  (loop [n 0]
    (let [info (runner/job-info r job-id)]
      (if (or (contains? #{:succeeded :failed} (:status info)) (>= n 200))
        info
        (do (Thread/sleep 15) (recur (inc n)))))))

(deftest curate-all-only-touches-configured-libraries
  (testing "curate-all syncs + recategorizes only the configured libraries and
            excludes grout-content"
    (let [synced      (atom [])
          categorized (atom [])]
      (with-redefs [pv-client/get-config         (fn [_] {:base-url "http://pv"
                                                          :libraries {:movies 1 :shows 2}})
                    pv-client/list-all-libraries (fn [_] [{:id 1 :name "movies"}
                                                          {:id 2 :name "shows"}
                                                          {:id 9 :name "grout-content"}])
                    catalog/update-libraries!    (fn [_ _] nil)
                    pv-media-sync/sync-library-from-pseudovision!
                    (fn [_ _ library _] (swap! synced conj library) {:synced 0})
                    curate/->TunabrainCuratorBackend (fn [_ _ _ _] ::backend)
                    curate/recategorize-library!
                    (fn [_ library _] (swap! categorized conj library) {})]
        (let [r      (runner/create {})
              ctx    {:job-runner r :catalog nil :pseudovision nil
                      :tunabrain nil :throttler nil :curation-config nil}
              resp   ((media/curate-all-handler ctx) {:parameters {:query {}}})
              job-id (get-in resp [:body :job :id])
              info   (await-terminal r job-id)]
          (is (= 202 (:status resp)))
          (is (= :succeeded (:status info)))
          (is (= #{"movies" "shows"} (set @synced))       "grout-content is not synced")
          (is (= #{"movies" "shows"} (set @categorized))  "grout-content is not recategorized")
          (runner/shutdown! r))))))

(deftest curate-all-requires-configured-libraries
  (testing "curate-all returns 400 when no libraries are configured"
    (with-redefs [pv-client/get-config (fn [_] {:base-url "http://pv"})]
      (let [resp ((media/curate-all-handler {:job-runner nil :catalog nil :pseudovision nil
                                             :tunabrain nil :throttler nil :curation-config nil})
                  {:parameters {:query {}}})]
        (is (= 400 (:status resp)))))))

;; ---------------------------------------------------------------------------
;; sync-from-pseudovision handler — runs ASYNC via the job runner.
;; Prior fix: it ran synchronously, hanging the caller for ~3 minutes against
;; a 222-row gap. Now it returns 202 with a :job immediately, and the actual
;; work — including :report-progress so the Jobs page can show progress —
;; happens in the runner.
;; ---------------------------------------------------------------------------

(deftest sync-from-pseudovision-returns-202-with-job
  (testing "POST /api/media/:library/sync-from-pseudovision returns 202 + a :job, not 200"
    (let [r (runner/create {})
          handler (media/sync-from-pseudovision-handler
                   {:job-runner r :catalog nil :pseudovision nil})
          resp (handler {:parameters {:path {:library "shows"}}})]
      (is (= 202 (:status resp)))
      (is (contains? (:body resp) :job)
          "body contains :job (JobSubmitResponse), not the old :synced/:message shape")
      (is (contains? (get-in resp [:body :job]) :id))
      (runner/shutdown! r))))

(deftest sync-from-pseudovision-runs-via-runner-not-inline
  (testing "the handler returns before sync-library-from-pseudovision! would block"
    ;; If the handler still ran the work inline, the response wouldn't come back
    ;; until after the redef'd sync fn returned. We hold the sync fn open on a
    ;; promise; the response must arrive while the promise is still unresolved.
    (let [gate (promise)
          r    (runner/create {})
          handler (media/sync-from-pseudovision-handler
                   {:job-runner r :catalog nil
                    :pseudovision {:config {:base-url "http://pv"}}})
          resp (future
                 (handler {:parameters {:path {:library "shows"}}}))]
      (with-redefs [pv-client/get-config (fn [_] {:base-url "http://pv"})
                    pv-media-sync/sync-library-from-pseudovision!
                    (fn [_ _ _ _]
                      (deref gate)        ; block until released
                      {:synced 0 :skipped 0 :errors []})]
        (Thread/sleep 100)                ; give the runner time to dispatch
        (let [r1 (deref resp 500 :timeout)]
          (is (not= :timeout r1)
              "handler returned immediately (not blocked on sync)"))
        (deliver gate {:synced 0 :skipped 0 :errors []})
        ;; let the runner finish so we can shut down cleanly
        (Thread/sleep 100))
      (runner/shutdown! r))))

(deftest sync-from-pseudovision-400-when-no-library
  (testing "library missing → 400 (not the previous throw + 500)"
    (let [r (runner/create {})
          handler (media/sync-from-pseudovision-handler
                   {:job-runner r :catalog nil :pseudovision nil})]
      (is (= 400 (:status (handler {:parameters {:path {}}}))))
      (runner/shutdown! r))))

;; ---------------------------------------------------------------------------
;; Pseudovision kind classification.
;;
;; Background: on 2026-07-18 we discovered that the sync-from-pseudovision job
;; "succeeded" but every show + most episodes silently failed to land in
;; tunarr-scheduler's media table because classify-item-kind and the
;; pseudovision-item->catalog-item :case compared against Clojure keywords
;; (:show/:movie/:episode) while PV's `:kind` actually arrives as a string
;; ("show"/"movie"/"episode") -- PV stores it as a Postgres enum exposed via
;; PostgREST. So every cond/case fell through to the default and the row was
;; inserted with media_type='show' (which violates media_media_type_check)
;; or item_kind='filler' (which violates chk_episode_numbers on episodes).
;;
;; These regression tests pin both the corrected keyword normalization and
;; the show→series media_type mapping. If either regresses, sync-from-pv
;; will silently break again and hundreds of items will fail to land in TS.
;; ---------------------------------------------------------------------------

(deftest classify-item-kind-show-as-string-is-series
  (testing "PV kind=\"show\" (string) → :series"
    (is (= :series
           (#'pv-media-sync/classify-item-kind
            {:kind "show" :parent-id nil :name "Gumball"})))))

(deftest classify-item-kind-show-as-keyword-is-series
  (testing "PV kind=:show (keyword) → :series (defensive: accept either form)"
    (is (= :series
           (#'pv-media-sync/classify-item-kind
            {:kind :show :parent-id nil :name "Gumball"})))))

(deftest classify-item-kind-movie-as-string
  (testing "PV kind=\"movie\" (string) → :movie (not :filler)"
    (is (= :movie
           (#'pv-media-sync/classify-item-kind
            {:kind "movie" :name "12 Angry Men"})))))

(deftest classify-item-kind-episode-with-parent
  (testing "PV kind=\"episode\" + parent-id (Jellyfin or PV) → :episode"
    (is (= :episode
           (#'pv-media-sync/classify-item-kind
            {:kind "episode"
             :parent-id 100
             :season-number 1
             :position 1
             :name "Pilot"})))))

(deftest classify-item-kind-pv-episode-with-only-parent-id
  (testing "PV kind=\"episode\" + parent-id but NO season-number/episode-number → :episode.
            Regression for the 2026-07-19 silent bug: PV's :episode rows don't
            carry :season-number/:episode-number (only :parent_id + :position),
            so the original cond branch (which required season+episode both non-nil)
            fell through to :filler. Live log tail surfaced this — see PR #118."
    (is (= :episode
           (#'pv-media-sync/classify-item-kind
            {:kind "episode"
             :parent-id 53781
             :position 20
             :name "Karate Island"})))))

(deftest classify-item-kind-orphan-episode-falls-to-filler
  (testing "PV kind=\"episode\" but no parent-id → :filler (lost episode, no
            parent to attach to). Demonstrates the new cond correctly demands
            a parent for the episode classification."
    (is (= :filler
           (#'pv-media-sync/classify-item-kind
            {:kind "episode"
             :parent-id nil
             :position 20
             :name "Orphaned"})))))

(deftest catalog-item-show-string-becomes-series-media-type
  (testing "PV kind=\"show\" maps media_type to :series (not :show), so the
            `media_media_type_check` CHECK constraint passes"
    (let [out (#'pv-media-sync/pseudovision-item->catalog-item
               {:id 51820
                :remote-key "6f8dee6e8463a34167babc45e467adb4"
                :kind "show"
                :name "The Amazing World of Gumball"
                :year 2011
                :parent-id nil
                :release-date "2011-05-03"}
              30)
          ;; ::media/type === :series, not :show and not :filler
          media-type-key (first (filter (fn [[k _]]
                                          (= k :tunarr.scheduler.media/type))
                                        out))
          item-kind-key (first (filter (fn [[k _]]
                                          (= k :tunarr.scheduler.media/item-kind))
                                       out))]
      (is (= :series (second media-type-key))
          "media_type must be :series so PostgreSQL CHECK allows it")
      (is (= :series (second item-kind-key))
          "item_kind must be :series so chk_episode_numbers doesn't fire")
      (is (= "The Amazing World of Gumball"
             (get out :tunarr.scheduler.media/name))
          "name is preserved through normalization"))))

(deftest catalog-item-movie-string-becomes-movie-media-type
  (testing "PV kind=\"movie\" + string → :movie media_type + :movie item_kind"
    (let [out (#'pv-media-sync/pseudovision-item->catalog-item
               {:id 50374
                :remote-key "0ac31cb1b1f44ecedc3c1be9e87a3f1fdb"
                :kind "movie"
                :name "12 Angry Men"
                :year 1957
                :release-date "1957-04-10"}
              30)
          media-type-key (first (filter (fn [[k _]]
                                          (= k :tunarr.scheduler.media/type))
                                        out))]
      (is (= :movie (second media-type-key))))))

(deftest catalog-item-pv-episode-gets-episode-item-kind
  (testing "PV kind=\"episode\" with parent_id+position and no season-number →
            produces a catalog row with :episode item_kind (not :filler) so
            the catalog INSERT no longer trips chk_episode_numbers. Regression
            for the silent 2026-07-19 episode sync issue."
    (let [out (#'pv-media-sync/pseudovision-item->catalog-item
               {:id 77397
                :remote-key "69aab4bccd673c50c6cccfb66abfdd9d"
                :kind "episode"
                :name "Karate Island"
                :year 2006
                :parent-id 53781
                :position 20
                :release-date "2006-01-01"}
              30)
          item-type-key (first (filter (fn [[k _]]
                                         (= k :tunarr.scheduler.media/type))
                                       out))
          item-kind-key (first (filter (fn [[k _]]
                                         (= k :tunarr.scheduler.media/item-kind))
                                       out))
          ep-num-key    (first (filter (fn [[k _]]
                                         (= k :tunarr.scheduler.media/episode-number))
                                       out))
          parent-id-key (first (filter (fn [[k _]]
                                         (= k :tunarr.scheduler.media/parent-id))
                                       out))]
      (is (= :episode (second item-type-key))  "media_type=:episode")
      (is (= :episode (second item-kind-key))  "item_kind=:episode (was :filler)")
      (is (= 20 (second ep-num-key))           ":position became episode_number")
      (is (= 53781 (second parent-id-key))     ":parent_id propagated"))))

;; ---------------------------------------------------------------------------
;; Grout-clips-as-filler + nil-name fallbacks
;;
;; Regression for the 2026-07-23 sync-from-pseudovision run that ingested
;; 2,579 Grout programs: 2,578 of them errored with `null value in column
;; "name" of relation "media"` and 2 with `media_media_type_check`
;; violation. Both are handled below.
;; ---------------------------------------------------------------------------

(deftest catalog-item-grout-program-becomes-filler
  (testing "PV kind=\"program\" with no parent/no episode numbers → :filler for
            both item_kind (drives chk_episode_numbers) and media_type (drives
            media_media_type_check). Without this the catalog row fails to
            insert with `new row for relation \"media\" violates check
            constraint \"media_media_type_check\"`."
    (let [out (#'pv-media-sync/pseudovision-item->catalog-item
               {:id          1340275
                :remote-key  "grout:e7c1e7e8-c2d8-43e6-a6d4-65b315c3aada"
                :kind        "program"
                :name        nil
                :year        nil
                :parent-id   nil
                :release-date nil}
              49)
          media-type-key (first (filter (fn [[k _]]
                                          (= k :tunarr.scheduler.media/type))
                                        out))
          item-kind-key (first (filter (fn [[k _]]
                                          (= k :tunarr.scheduler.media/item-kind))
                                       out))]
      (is (= :filler (second media-type-key))
          "media_type must be :filler so media_media_type_check passes")
      (is (= :filler (second item-kind-key))
          "item_kind must be :filler so chk_episode_numbers doesn't fire"))))

(deftest catalog-item-nil-name-gets-traceable-placeholder
  (testing "PV item with :name nil (typical Grout clip pre-Tunabrain-enrichment)
            gets a deterministic placeholder containing the remote-key, so the
            catalog's `name VARCHAR NOT NULL CHECK (name <> '')` accepts the
            row and operators can find the underlying Grout clip from the
            catalog. The placeholder will be replaced by the real title on a
            subsequent sync once Tunabrain enrichment produces one."
    (let [rk "grout:6d320143-4bce-4d3b-83c5-3a5e2d44c8af"
          out (#'pv-media-sync/pseudovision-item->catalog-item
               {:id          1340300
                :remote-key  rk
                :kind        "program"
                :name        nil
                :year        nil
                :parent-id   nil
                :release-date nil}
              49)
          name-key (first (filter (fn [[k _]]
                                    (= k :tunarr.scheduler.media/name))
                                  out))]
      (is (= (str "Unnamed (" rk ")")
             (second name-key))
          "name is the deterministic placeholder, not nil and not empty")))

  (testing "Empty-string :name is also replaced (the CHECK also forbids '')"
    (let [rk "grout:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          out (#'pv-media-sync/pseudovision-item->catalog-item
               {:id          1340301
                :remote-key  rk
                :kind        "program"
                :name        ""
                :year        nil
                :parent-id   nil
                :release-date nil}
              49)
          name-key (first (filter (fn [[k _]]
                                    (= k :tunarr.scheduler.media/name))
                                  out))]
      (is (= (str "Unnamed (" rk ")")
             (second name-key))
          "empty-string :name is also replaced by the placeholder")))

  (testing "Non-empty :name is preserved unchanged (no placeholder substitution)"
    (let [out (#'pv-media-sync/pseudovision-item->catalog-item
               {:id          1340302
                :remote-key  "grout:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                :kind        "program"
                :name        "Tasty Recipe 2024"
                :year        2024
                :parent-id   nil
                :release-date "2024-03-15"}
              49)
          name-key (first (filter (fn [[k _]]
                                    (= k :tunarr.scheduler.media/name))
                                  out))]
      (is (= "Tasty Recipe 2024" (second name-key))
          "real :name is passed through verbatim"))))

;; ---------------------------------------------------------------------------
;; /api/jobs/:id must surface :result. Regression for the silent failure mode
;; that masked the 2026-07-18 sync issue: the runner stored :result in memory
;; but the Job Malli schema didn't include it, so reitit stripped it before
;; serialization → /api/jobs/:id returned :status:succeeded with no counts.
;; ---------------------------------------------------------------------------

(deftest job-result-survives-api-serialization
  (testing "submitting a job whose task-fn returns a result map exposes that
            map under :result on the job record returned by job-info"
    (let [r (runner/create {})
          sent (runner/submit-job!
                r
                {:type :media/sync-from-pseudovision}
                (fn [_]
                  {:synced 41
                   :skipped 0
                   :errors [{:item-id 51820
                             :name "Gumball"
                             :error "boom"}]}))
          job-info (await-terminal r (:id sent))]
      (is (= :succeeded (:status job-info)))
      ;; The runner's ->public-job emits :result when status is terminal;
      ;; formerly the Job Malli schema dropped it before serialization.
      (is (= 41 (:synced (or (:result job-info) {})))
          ":result is preserved by the public-job serializer for the Job schema")
      (is (= "boom"
             (-> job-info :result :errors first :error)))
      (runner/shutdown! r))))

;; ---------------------------------------------------------------------------
;; Followup regression — the JOB schema must admit a non-empty :result map.
;;
;; PR #117 added [:result {:optional true} [:maybe :map]] but I wrote
;; `[:maybe :map]` instead of `[:maybe [:map {:closed false}]]`. Malli's
;; `[:maybe :map]` is closed-empty by default: it permits the empty map and
;; strips every other key. Live verification on 2026-07-19 confirmed the
;; runner was storing `{:synced … :skipped … :errors …}` but the API
;; returned `result: {}`. This test feeds a finished-job-shaped map directly
;; through the s/Job schema (the same one Reitit applies to /api/jobs/:id
;; responses) and asserts the keys survive.
;; ---------------------------------------------------------------------------

(deftest job-schema-preserves-result-keys
  (testing "s/Job accepts a non-empty :result map with arbitrary keys"
    (let [job-with-result {:id "abc-123"
                           :type :media/sync-from-pseudovision
                           :status :succeeded
                           :metadata {}
                           :progress {:phase "syncing" :page 25 :item 436}
                           :duration-ms 172000
                           :created-at "2026-07-19T01:15:54Z"
                           :started-at "2026-07-19T01:15:54Z"
                           :completed-at "2026-07-19T01:18:46Z"
                           :result {:synced 41
                                    :skipped 0
                                    :errors [{:item-id 51820
                                              :name "Gumball"
                                              :error "boom"}]}}
          ;; Malli 0.20: `m/validate` returns boolean (true=valid, false=invalid).
          ;; `m/explain` returns nil on valid or a map of `:errors` on invalid.
          ;; We use `explain` so the failure message can include the errors.
          errors (m/explain s/Job job-with-result)]
      (is (not errors)
          (str "s/Job must accept a finished job map with non-empty :result; "
               "got explain errors: " (pr-str errors))))))
