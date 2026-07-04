(ns tunarr.scheduler.http.api.media-test
  (:require [clojure.test :refer [deftest is testing]]
            [tunarr.scheduler.http.api.media :as media]
            [tunarr.scheduler.media :as media-ns]
            [tunarr.scheduler.media.catalog :as catalog]))

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
