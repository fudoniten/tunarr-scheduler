(ns tunarr.scheduler.tunabrain-test
  (:require [clojure.test :refer [deftest is testing]]
            [tunarr.scheduler.tunabrain :as tunabrain]
            [tunarr.scheduler.media :as media]
            [clj-http.client :as http])
  (:import [tunarr.scheduler.tunabrain TunabrainClient]))

;; Note: These tests use mocking since we don't want to make actual HTTP requests

(deftest create-client-test
  (testing "create! creates a tunabrain client with default endpoint"
    (let [client (tunabrain/create! {})]
      (is (instance? TunabrainClient client))
      (is (= "http://localhost:8080" (:endpoint client)))))

  (testing "create! creates a tunabrain client with custom endpoint"
    (let [client (tunabrain/create! {:endpoint "https://tunabrain.example.com"})]
      (is (instance? TunabrainClient client))
      (is (= "https://tunabrain.example.com" (:endpoint client)))))

  (testing "create! sanitizes trailing slashes from endpoint"
    (let [client (tunabrain/create! {:endpoint "https://tunabrain.example.com///"})]
      (is (= "https://tunabrain.example.com" (:endpoint client))))))

(deftest create-client-with-http-opts-test
  (testing "create! accepts custom HTTP options"
    (let [http-opts {:timeout 5000 :socket-timeout 10000}
          client (tunabrain/create! {:endpoint "https://example.com"
                                     :http-opts http-opts})]
      (is (= http-opts (:http-opts client))))))

;; ===========================================================================
;; normalize-override — random:<category> kebab-case (Layer 2 of the
;; random-category fix; lookup-side fallback is fudoniten/pseudovision#116).
;; The helpers are private; we reach them via the var (`@#'`) so the test
;; stays in lockstep with the production function — if a var is renamed
;; or removed, the test will throw a var-resolution error at load time
;; instead of silently testing a different function.
;; ===========================================================================

(def ^:private normalize-override-fn
  @#'tunarr.scheduler.tunabrain/normalize-override)
(def ^:private normalize-random-media-id-fn
  @#'tunarr.scheduler.tunabrain/normalize-random-media-id)
(def ^:private kebab-case-fn
  @#'tunarr.scheduler.tunabrain/kebab-case)

;; ---------------------------------------------------------------------------
;; kebab-case — the dimension-naming helper
;; ---------------------------------------------------------------------------

(deftest kebab-case-canonical-examples
  (testing "kebab-case produces the canonical PV tag form"
    (is (= "sci-fi-and-fantasy"   (kebab-case-fn "Sci-Fi & Fantasy"))
        "& becomes 'and' and spaces collapse to hyphens")
    (is (= "action-and-adventure" (kebab-case-fn "Action & Adventure"))
        "multi-word with `&` is the load-bearing case — the 5 channels with
         bad overrides (galaxy, spectrum, chronicles, infobytes, toontown)
         all hit this transformation")
    (is (= "sci-fi"               (kebab-case-fn "Sci-Fi"))
        "single-word with hyphen — non-alphanumerics collapse to a single `-`")
    (is (= "comedy"               (kebab-case-fn "Comedy"))
        "lowercase pass-through for the simple Title-Case form")
    (is (= "comedy"               (kebab-case-fn "comedy"))
        "already-kebab inputs are unchanged (idempotent)")))

(deftest kebab-case-edge-cases
  (testing "kebab-case is nil-safe and trims whitespace"
    (is (nil? (kebab-case-fn nil))
        "nil input returns nil — used inside `cond->` so the absence of a
         value must be a no-op, not a NullPointerException")
    (is (= "" (kebab-case-fn ""))
        "empty string returns empty string")
    (is (= "drama" (kebab-case-fn "  Drama  "))
        "leading/trailing whitespace is trimmed before transformation"))
  (testing "kebab-case is idempotent under repeated calls"
    (is (= (kebab-case-fn (kebab-case-fn "Sci-Fi & Fantasy"))
           (kebab-case-fn "Sci-Fi & Fantasy"))
        "applying kebab-case twice yields the same result as once — the
         already-kebab form is the fixed point of the transformation")))

;; ---------------------------------------------------------------------------
;; normalize-random-media-id — applies kebab-case only to random:<category>
;; ---------------------------------------------------------------------------

(deftest normalize-random-media-id-canonicalizes-the-category
  (testing "random:<Human-Readable> becomes random:<kebab>"
    (is (= "random:sci-fi-and-fantasy"   (normalize-random-media-id-fn "random:Sci-Fi & Fantasy")))
    (is (= "random:comedy"               (normalize-random-media-id-fn "random:Comedy")))
    (is (= "random:action-and-adventure" (normalize-random-media-id-fn "random:Action & Adventure")))
    (is (= "random:documentary"          (normalize-random-media-id-fn "random:Documentary")))))

(deftest normalize-random-media-id-leaves-other-shapes-alone
  (testing "non-random media_ids are returned verbatim"
    (is (= "series:abc123" (normalize-random-media-id-fn "series:abc123"))
        "series:<id> is opaque to the normalizer — it should pass through")
    (is (= "movie:abc123"  (normalize-random-media-id-fn "movie:abc123"))
        "movie:<id> is also opaque — UUIDs and PV ids can contain `&`-like
         or `-`-like characters that we must not touch"))
  (testing "already-kebab random: is a no-op (idempotent)"
    (is (= "random:sci-fi-and-fantasy"
           (normalize-random-media-id-fn "random:sci-fi-and-fantasy"))
        "if the override was already generated in canonical form, running
         it through the normalizer again must not change it (the second
         pass must be idempotent, not introduce double-dashes or stray
         whitespace)"))
  (testing "nil and non-string inputs are safe"
    (is (nil? (normalize-random-media-id-fn nil))
        "nil is the most-common case when content/media_id is absent in
         a non-random override — the cond-> guard calls it with nil
         values during storage of every series: and movie: override")
    (is (= 42 (normalize-random-media-id-fn 42))
        "non-string is left alone — the contract is `(string? ...)` guarded
         in normalize-override, so anything weird here is a programmer
         error, not data we try to massage")))

;; ---------------------------------------------------------------------------
;; normalize-override — the integration: scope trim + media_id normalization
;; ---------------------------------------------------------------------------

(deftest normalize-override-strips-nil-scopes-and-kebabs-random
  (testing "scope-key trimming and media_id kebab-case compose cleanly"
    (let [override {:scope     {:date "2026-07-04" :days nil :effective_start nil}
                    :content   {:media_id "random:Sci-Fi & Fantasy"
                                :strategy "random"
                                :label    "Independence Day Sci-Fi Marathon"}}
          out      (normalize-override-fn override)]
      (is (= {:date "2026-07-04"} (:scope out))
          "nil-valued scope keys (`:days`, `:effective_start`) are dropped
           so the override satisfies the closed OverrideScope contract")
      (is (= "random:sci-fi-and-fantasy" (get-in out [:content :media_id]))
          "the random:<category> media_id is kebab-cased to match the
           canonical PV tag form, ready for storage")
      (is (= "Independence Day Sci-Fi Marathon" (get-in out [:content :label]))
          "other content fields are not touched (label/strategy/etc)"))))

(deftest normalize-override-passes-through-series-and-movie
  (testing "series:<id> and movie:<id> overrides are stored verbatim"
    (let [series {:scope   {:date "2026-07-04"}
                  :content {:media_id "series:e7712464e5bb99b7faa29eb60ad6d8a1"
                            :strategy "specific"
                            :label    "Agatha Christie's Poirot Marathon"}}
          movie  {:scope   {:date "2026-07-04"}
                  :content {:media_id "movie:6f4faf68aabc64eb4d9b54b33627a742"
                            :strategy "specific"
                            :label    "Independence Day Special"}}
          s-out  (normalize-override-fn series)
          m-out  (normalize-override-fn movie)]
      (is (= "series:e7712464e5bb99b7faa29eb60ad6d8a1" (get-in s-out [:content :media_id]))
          "series:<id> passes through unchanged — the normalizer only
           touches `random:<category>` media_ids")
      (is (= "movie:6f4faf68aabc64eb4d9b54b33627a742"  (get-in m-out [:content :media_id]))
          "movie:<id> passes through unchanged for the same reason"))))

(deftest normalize-override-leaves-existing-good-data-intact
  (testing "an override already in canonical form is unchanged end-to-end"
    (let [good   {:scope   {:date "2026-07-04"}
                  :content {:media_id "random:comedy"
                            :strategy "random"}}
          out    (normalize-override-fn good)]
      (is (= "random:comedy" (get-in out [:content :media_id]))
          "already-kebab random: must NOT be transformed — round-tripping
           through the normalizer must be a no-op (idempotency is part
           of the contract; otherwise re-running monthly would corrupt
           previously-stored clean overrides)"))))

(deftest normalize-override-survives-weird-inputs
  (testing "defensive guards — bad data must not throw"
    (is (= {} (normalize-override-fn {}))
        "empty map normalises to empty map (no :scope, no :content)")
    (is (= {:content nil} (normalize-override-fn {:content nil}))
        "nil :content is fine — the second cond-> branch is false")
    (is (= {:content {:media_id 42}} (normalize-override-fn {:content {:media_id 42}}))
        "non-string media_id is left alone (storage layer validates the type)")))

;; ---------------------------------------------------------------------------
;; End-to-end shape test — the wire format that ends up in the DB
;; ---------------------------------------------------------------------------

(deftest normalized-override-round-trips-through-the-override-scope-contract
  (testing "after normalize-override, the result is exactly what storage expects"
    (let [raw     {:scope            {:date "2026-07-04" :days nil}
                   :override_id      "galaxy-2026-07-ovr-2"
                   :content          {:media_id "random:Action & Adventure"
                                      :strategy "random"
                                      :marathon false
                                      :category_filters []
                                      :label    "Weekend Action Block"
                                      :notes    []}
                   :mode             "replace"
                   :priority         0
                   :note             "Weekend Action block"}
          out     (normalize-override-fn raw)]
      ;; Scope key dropped (was :days, nil)
      (is (not (contains? (:scope out) :days)))
      ;; Scope kept the non-nil key
      (is (contains? (:scope out) :date))
      ;; Media_id kebab-cased
      (is (= "random:action-and-adventure" (get-in out [:content :media_id])))
      ;; Everything else is preserved
      (is (= "galaxy-2026-07-ovr-2"        (:override_id out)))
      (is (= "replace"                     (:mode out)))
      (is (= 0                            (:priority out)))
      (is (= "Weekend Action block"       (:note out))))))

(deftest request-tags-simple-response-test
  (testing "request-tags! parses simple string array response"
    (with-redefs [http/post (fn [_ _]
                             {:status 200
                              :body "[\"action\", \"adventure\", \"thriller\"]"})]
      (let [client (tunabrain/create! {:endpoint "http://test.local"})
            media {::media/name "Test Movie"
                   ::media/overview "A test movie"}
            result (tunabrain/request-tags! client media)]
        (is (= [:action :adventure :thriller] (:tags result)))))))

(deftest request-tags-detailed-response-test
  (testing "request-tags! parses detailed map response with filtered tags"
    (with-redefs [http/post (fn [_ _]
                             {:status 200
                              :body "{\"tags\": [\"action\", \"adventure\"],
                                      \"filtered_tags\": [\"inappropriate\"],
                                      \"taglines\": [\"An epic journey\"]}"})]
      (let [client (tunabrain/create! {:endpoint "http://test.local"})
            media {::media/name "Test Movie"}
            result (tunabrain/request-tags! client media)]
        (is (= [:action :adventure] (:tags result)))
        (is (= [:inappropriate] (:filtered-tags result)))
        (is (= ["An epic journey"] (:taglines result)))))))

(deftest request-tags-with-catalog-tags-test
  (testing "request-tags! includes existing catalog tags in request"
    (let [posted-data (atom nil)]
      (with-redefs [http/post (fn [_ opts]
                               (reset! posted-data (clojure.walk/keywordize-keys
                                                   (cheshire.core/parse-string (:body opts))))
                               {:status 200
                                :body "[\"action\"]"})]
        (let [client (tunabrain/create! {:endpoint "http://test.local"})
              media {::media/name "Test Movie"}]
          (tunabrain/request-tags! client media :catalog-tags [:existing-tag])
          (is (= [:existing-tag] (:existing_tags @posted-data))))))))

(deftest request-tags-error-handling-test
  (testing "request-tags! throws on non-2xx response"
    (with-redefs [http/post (fn [_ _]
                             {:status 500
                              :body "Internal Server Error"})]
      (let [client (tunabrain/create! {:endpoint "http://test.local"})
            media {::media/name "Test Movie"}]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                             #"tunabrain request failed: 500"
                             (tunabrain/request-tags! client media)))))))

(deftest request-categorization-test
  (testing "request-categorization! parses categorization response"
    (with-redefs [http/post (fn [_ _]
                             {:status 200
                              :body "{\"mappings\": [{\"channel_name\": \"action_channel\",
                                                       \"reasons\": [\"High action content\"]}],
                                      \"dimensions\": [{\"dimension\": \"mood\",
                                                        \"values\": [\"exciting\"],
                                                        \"notes\": [\"Fast paced\"]}]}"})]
      (let [client (tunabrain/create! {:endpoint "http://test.local"})
            media {::media/name "Test Movie"}
            result (tunabrain/request-categorization! client media
                                                     :categories {:mood {:description "Overall mood"
                                                                         :values ["exciting" "calm"]}})]
        (is (= 1 (count (:mappings result))))
        (is (= :action_channel (get-in result [:mappings 0 ::media/channel-name])))
        (is (= "High action content" (get-in result [:mappings 0 ::media/rationale])))
        (is (contains? (:dimensions result) :mood))
        (is (= :exciting (get-in result [:dimensions :mood 0 ::media/category-value])))
        (is (= "Fast paced" (get-in result [:dimensions :mood 0 ::media/rationale])))))))

;; ---------------------------------------------------------------------------
;; MediaContext threading (handoff: context on /tags and /categorize)
;; ---------------------------------------------------------------------------

(deftest request-tags-sends-and-parses-context-test
  (testing "request-tags! sends stored context and parses the returned context"
    (let [posted (atom nil)]
      (with-redefs [http/post (fn [_ opts]
                                (reset! posted (clojure.walk/keywordize-keys
                                                (cheshire.core/parse-string (:body opts))))
                                {:status 200
                                 :body "{\"tags\": [\"crime-drama\"],
                                         \"context\": {\"text\": null,
                                                       \"links\": [\"https://en.wikipedia.org/wiki/Juice_(1992_film)\"],
                                                       \"summary\": \"Juice (1992) is a crime drama.\",
                                                       \"source\": \"wikipedia\"}}"})]
        (let [client (tunabrain/create! {:endpoint "http://test.local"})
              media  {::media/name "Juice"}
              result (tunabrain/request-tags!
                      client media
                      :context {:summary "Juice (1992) is a violent crime drama."
                                :links [] :operator-edited true})]
          ;; request carries only the wire-relevant fields (summary here)
          (is (= "Juice (1992) is a violent crime drama."
                 (get-in @posted [:context :summary])))
          (is (nil? (get-in @posted [:context :operator-edited]))
              "internal-only keys are not sent to Tunabrain")
          ;; response context is parsed back out
          (is (= [:crime-drama] (:tags result)))
          (is (= "wikipedia" (get-in result [:context :source])))
          (is (= ["https://en.wikipedia.org/wiki/Juice_(1992_film)"]
                 (get-in result [:context :links]))))))))

(deftest request-tags-omits-empty-context-test
  (testing "request-tags! omits :context entirely when the stored context is empty"
    (let [posted (atom nil)]
      (with-redefs [http/post (fn [_ opts]
                                (reset! posted (clojure.walk/keywordize-keys
                                                (cheshire.core/parse-string (:body opts))))
                                {:status 200 :body "{\"tags\": []}"})]
        (let [client (tunabrain/create! {:endpoint "http://test.local"})]
          (tunabrain/request-tags! client {::media/name "X"}
                                   :context {:text nil :links [] :summary nil})
          (is (not (contains? @posted :context))
              "an empty context must not be sent (preserves Wikipedia auto-search)"))))))

(deftest request-categorization-sends-and-parses-context-test
  (testing "request-categorization! sends context and parses the returned context"
    (let [posted (atom nil)]
      (with-redefs [http/post (fn [_ opts]
                                (reset! posted (clojure.walk/keywordize-keys
                                                (cheshire.core/parse-string (:body opts))))
                                {:status 200
                                 :body "{\"dimensions\": [],
                                         \"context\": {\"links\": [\"https://en.wikipedia.org/wiki/Juice_(1992_film)\"],
                                                       \"summary\": \"resolved\",
                                                       \"source\": \"provided-link\"}}"})]
        (let [client (tunabrain/create! {:endpoint "http://test.local"})
              result (tunabrain/request-categorization!
                      client {::media/name "Juice"}
                      :context {:links ["https://en.wikipedia.org/wiki/Juice_(1992_film)"]})]
          (is (= ["https://en.wikipedia.org/wiki/Juice_(1992_film)"]
                 (get-in @posted [:context :links])))
          (is (= "provided-link" (get-in result [:context :source]))))))))

(deftest request-tag-triage-test
  (testing "request-tag-triage! parses triage response"
    (with-redefs [http/post (fn [_ _]
                             {:status 200
                              :body "{\"decisions\": [{\"tag\": \"action\",
                                                       \"action\": \"keep\",
                                                       \"reason\": \"Commonly used\"},
                                                      {\"tag\": \"inappropriate\",
                                                       \"action\": \"remove\",
                                                       \"reason\": \"Violates policy\"}]}"})]
      (let [client (tunabrain/create! {:endpoint "http://test.local"})
            tag-samples [{:tag "action" :usage_count 100 :example_titles ["Movie 1"]}
                        {:tag "inappropriate" :usage_count 5 :example_titles ["Movie 2"]}]
            result (tunabrain/request-tag-triage! client tag-samples :target-limit 50 :debug true)]
        (is (= 2 (count (:decisions result))))
        (is (= :keep (get-in result [:decisions 0 :action])))
        (is (= :remove (get-in result [:decisions 1 :action])))))))

(deftest request-tag-audit-test
  (testing "request-tag-audit! parses audit response"
    (with-redefs [http/post (fn [_ _]
                             {:status 200
                              :body "{\"tags_to_delete\": [{\"tag\": \"inappropriate\",
                                                            \"reason\": \"Violates content policy\"}]}"})]
      (let [client (tunabrain/create! {:endpoint "http://test.local"})
            tags [:action :comedy :inappropriate]
            result (tunabrain/request-tag-audit! client tags)]
        (is (= 1 (count (:recommended-for-removal result))))
        (is (= "inappropriate" (get-in result [:recommended-for-removal 0 :tag])))
        (is (= "Violates content policy" (get-in result [:recommended-for-removal 0 :reason])))))))

(deftest request-tag-triage-omits-absent-optional-fields-test
  (testing "request-tag-triage! omits target_limit/debug when not provided
            (upstream's debug is a non-nullable bool that rejects null)"
    (let [posted-data (atom nil)]
      (with-redefs [http/post (fn [_ opts]
                               (reset! posted-data (clojure.walk/keywordize-keys
                                                   (cheshire.core/parse-string (:body opts))))
                               {:status 200
                                :body "{\"decisions\": []}"})]
        (let [client (tunabrain/create! {:endpoint "http://test.local"})]
          (tunabrain/request-tag-triage! client [{:tag "action" :usage_count 1 :example_titles []}])
          (is (not (contains? @posted-data :target_limit)))
          (is (not (contains? @posted-data :debug))))))))

(deftest request-tag-audit-converts-keywords-to-strings-test
  (testing "request-tag-audit! converts keyword tags to strings in request"
    (let [posted-data (atom nil)]
      (with-redefs [http/post (fn [_ opts]
                               (reset! posted-data (clojure.walk/keywordize-keys
                                                   (cheshire.core/parse-string (:body opts))))
                               {:status 200
                                :body "{\"tags_to_delete\": []}"})]
        (let [client (tunabrain/create! {:endpoint "http://test.local"})]
          (tunabrain/request-tag-audit! client [:action :comedy])
          (is (= ["action" "comedy"] (:tags @posted-data))))))))

(deftest client-implements-closeable-test
  (testing "TunabrainClient implements Closeable"
    (let [client (tunabrain/create! {})]
      (is (instance? java.io.Closeable client))
      ;; Should not throw
      (.close client))))

(deftest endpoint-construction-test
  (testing "requests construct correct endpoint URLs"
    (let [called-urls (atom [])]
      (with-redefs [http/post (fn [url _]
                               (swap! called-urls conj url)
                               {:status 200
                                :body (if (re-find #"/categorize$" url)
                                        "{\"dimensions\": [], \"mappings\": []}"
                                        (if (re-find #"/tag-governance/triage$" url)
                                          "{\"decisions\": []}"
                                          (if (re-find #"/tags/audit$" url)
                                            "{\"tags_to_delete\": []}"
                                            "[]")))})]
        (let [client (tunabrain/create! {:endpoint "https://api.example.com"})
              media {::media/name "Test"}]
          (tunabrain/request-tags! client media)
          (is (= "https://api.example.com/tags" (first @called-urls)))

          (reset! called-urls [])
          (tunabrain/request-categorization! client media)
          (is (= "https://api.example.com/categorize" (first @called-urls)))

          (reset! called-urls [])
          (tunabrain/request-tag-triage! client [])
          (is (= "https://api.example.com/tag-governance/triage" (first @called-urls)))

          (reset! called-urls [])
          (tunabrain/request-tag-audit! client [:tag])
          (is (= "https://api.example.com/tags/audit" (first @called-urls))))))))

(deftest http-options-passed-through-test
  (testing "HTTP options are passed through to clj-http"
    (let [received-opts (atom nil)]
      (with-redefs [http/post (fn [_ opts]
                               (reset! received-opts opts)
                               {:status 200 :body "[]"})]
        (let [client (tunabrain/create! {:endpoint "http://test.local"
                                        :http-opts {:timeout 5000}})
              media {::media/name "Test"}]
          (tunabrain/request-tags! client media)
          (is (= 5000 (:timeout @received-opts))))))))
