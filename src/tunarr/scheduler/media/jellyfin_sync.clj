(ns tunarr.scheduler.media.jellyfin-sync
  "Sync tags from catalog to Jellyfin via jellyfin-sidekick service"
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [cemerick.url :as url]
            [camel-snake-kebab.core :refer [->PascalCaseString]]
            [taoensso.timbre :as log]
            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.media.catalog :as catalog]))

(def tag-keyword->string ->PascalCaseString)

(defn- sidekick-request
  "Make a request to jellyfin-sidekick service"
  [sidekick-url method path & {:keys [body]}]
  (let [url (str (url/url sidekick-url path))
        opts (cond-> {:headers {"Content-Type" "application/json"}
                      :throw-exceptions false}
               body (assoc :body (json/generate-string body)))]
    (log/debug "Jellyfin-sidekick request" {:method method :url url})
    (case method
      :get (http/get url opts)
      :post (http/post url opts)
      (throw (ex-info "Unsupported HTTP method" {:method method})))))

(defn update-item-tags!
  "Update tags for a single Jellyfin item via jellyfin-sidekick"
  [sidekick-url item-id tags]
  (let [tag-strings (mapv tag-keyword->string tags)
        path (str "api/items/" item-id "/tags")]
    (log/info "Updating Jellyfin item tags via sidekick" {:item-id item-id :tags tag-strings})
    
    (let [response (sidekick-request sidekick-url :post path 
                                    :body {:tags tag-strings})]
      (if (= 200 (:status response))
        (do
          (log/info "Successfully updated tags for item" {:item-id item-id})
          {:success true :item-id item-id})
        (do
          (log/error "Failed to update tags for item"
                    {:item-id item-id
                     :status (:status response)
                     :body (:body response)})
          {:success false
           :item-id item-id
           :error (:body response)
           :status (:status response)})))))

(defn sync-library-tags!
  "Sync all tags from catalog to Jellyfin via jellyfin-sidekick.
   
   Iterates over all media items in the catalog for the specified library
   and pushes their current tags to jellyfin-sidekick, which writes NFO files
   and triggers Jellyfin to refresh metadata."
  [catalog config library opts]
  (let [{:keys [report-progress]} opts
        sidekick-url (or (:sidekick-url config)
                        "http://jellyfin-sidekick.arr.svc.cluster.local:8080")
        media-items (catalog/get-media-by-library catalog library)
        total-count (count media-items)
        _ (log/info (format "Syncing tags for %d items in library %s via jellyfin-sidekick" 
                           total-count library))
        _ (log/info "Using jellyfin-sidekick at" {:url sidekick-url})
        results (atom {:synced 0 :failed 0 :errors []})]
    
    (doseq [[idx item] (map-indexed vector media-items)]
      (let [item-id (::media/id item)
            tags (or (catalog/get-media-tags catalog item-id) [])]
        (when report-progress
          (report-progress {:phase :syncing
                           :current (inc idx)
                           :total total-count
                           :item item-id}))
        
        (let [result (update-item-tags! sidekick-url item-id tags)]
          (if (:success result)
            (swap! results update :synced inc)
            (do
              (swap! results update :failed inc)
              (swap! results update :errors conj
                    {:item-id item-id :error (:error result)}))))))
    
    (let [final-results @results]
      (log/info (format "Tag sync complete: %d synced, %d failed"
                       (:synced final-results)
                       (:failed final-results)))
      (when (pos? (:failed final-results))
        (log/warn "Failed items:" (:errors final-results)))
      final-results)))
