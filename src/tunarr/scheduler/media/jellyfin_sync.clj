(ns tunarr.scheduler.media.jellyfin-sync
  "Sync tags from catalog to Jellyfin using HTTP API"
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [cemerick.url :as url]
            [camel-snake-kebab.core :refer [->PascalCaseString]]
            [taoensso.timbre :as log]
            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.media.catalog :as catalog]))

(defn jellyfin-authenticated-request
  "Make an authenticated request to Jellyfin"
  [{:keys [api-key] :as config} method url & {:keys [body query-params]}]
  (when-not api-key
    (log/error "No API key found in config!" {:config-keys (keys config)})
    (throw (ex-info "Jellyfin API key not configured" {:config config})))
  (let [opts (cond-> {:headers {"X-Emby-Token" api-key
                                "Content-Type" "application/json"}
                      :throw-exceptions false}
               body (assoc :body (json/generate-string body))
               query-params (assoc :query-params query-params))]
    (log/debug "Jellyfin request" {:method method :url url :has-api-key (boolean api-key)})
    (case method
      :get (http/get url opts)
      :post (http/post url opts)
      (throw (ex-info "Unsupported HTTP method" {:method method})))))

(defn- format-guid
  "Format a hex string as a UUID with dashes.
  Jellyfin expects UUIDs in the format xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
  [id-string]
  (let [cleaned (str/replace id-string #"-" "")]
    (if (= 32 (count cleaned))
      (format "%s-%s-%s-%s-%s"
              (subs cleaned 0 8)
              (subs cleaned 8 12)
              (subs cleaned 12 16)
              (subs cleaned 16 20)
              (subs cleaned 20 32))
      id-string)))

(defn- build-item-url
  "Build the URL for updating a Jellyfin item"
  [base-url item-id]
  (str (url/url base-url "Items" (format-guid item-id))))

(defn- build-search-url
  "Build URL for searching items"
  [base-url & {:keys [params]}]
  (-> (url/url base-url "Items")
      (assoc :query params)
      str))

(defn- get-item
  "Get the full item data from Jellyfin by searching for it by ID.
   Uses the Items search endpoint with the item ID as a filter.
   Gets ALL fields to ensure we have a complete item for updating."
  [config item-id]
  (let [guid (format-guid item-id)
        ;; Don't specify Fields parameter to get all fields
        search-url (build-search-url (:base-url config) 
                                     :params {:Ids guid})]
    (log/debug "Searching for Jellyfin item" {:item-id item-id :url search-url})
    (let [response (jellyfin-authenticated-request config :get search-url)]
      (if (= 200 (:status response))
        (when-let [result (json/parse-string (:body response) true)]
          (when-let [items (:Items result)]
            (first items)))
        (do
          (log/error "Failed to search for item in Jellyfin" 
                    {:item-id item-id 
                     :status (:status response)
                     :body (:body response)})
          nil)))))

(def tag-keyword->string ->PascalCaseString)

(defn update-item-tags!
  "Update tags for a single Jellyfin item"
  [config item-id tags]
  (let [url (build-item-url (:base-url config) item-id)
        tag-strings (mapv tag-keyword->string tags)]
    (log/info "Updating Jellyfin item tags" {:item-id item-id :tags tag-strings})
    ;; First, GET the current item data
    (if-let [current-item (get-item config item-id)]
      (let [;; Update the Tags field with new tags
            updated-item (assoc current-item :Tags tag-strings)]
        (log/debug "Retrieved item" {:item-id item-id 
                                      :current-tags (:Tags current-item)
                                      :item-keys (keys current-item)})
        (log/debug "Sending update to Jellyfin" {:item-id item-id 
                                                  :url url
                                                  :new-tags (:Tags updated-item)})
        (let [response (jellyfin-authenticated-request config :post url :body updated-item)]
          (if (= 204 (:status response))
            (do
              (log/info "Successfully updated tags for item" {:item-id item-id})
              {:success true :item-id item-id})
            (do
              (log/error "Failed to update tags for item"
                        {:item-id item-id
                         :status (:status response)
                         :body (:body response)})
              (log/debug (format "Request:\n%s" (with-out-str (pprint updated-item))))
              {:success false
               :item-id item-id
               :error (:body response)
               :status (:status response)}))))
      (do
        (log/error "Failed to get item from Jellyfin" {:item-id item-id})
        {:success false
         :item-id item-id
         :error "Item not found in Jellyfin"}))))

(defn sync-library-tags!
  "Sync all tags from catalog to Jellyfin for a given library.
  
   Iterates over all media items in the catalog for the specified library
   and pushes their current tags to Jellyfin, replacing all existing tags."
  [catalog config library opts]
  (let [{:keys [report-progress]} opts
        media-items (catalog/get-media-by-library catalog library)
        total-count (count media-items)
        _ (log/info (format "Syncing tags for %d items in library %s" total-count library))
        results (atom {:synced 0 :failed 0 :errors []})]
    
    (doseq [[idx item] (map-indexed vector media-items)]
      (let [item-id (::media/id item)
            tags (or (catalog/get-media-tags catalog item-id) [])]
        (when report-progress
          (report-progress {:phase :syncing
                           :current (inc idx)
                           :total total-count
                           :item item-id}))
        
        (let [result (update-item-tags! config item-id tags)]
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
