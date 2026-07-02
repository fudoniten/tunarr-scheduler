(ns tunarr.scheduler.backends.pseudovision.collections
  "Pseudovision collection management for bumper registration."
  ;; Provides helpers to create/find manual collections named 'Bumpers: <channel>'
  ;; and add media items to them so Pseudovision's gap-filler can discover them.
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

(defn- pv-request
  "Make an authenticated request against Pseudovision.
   `method` is :get, :post, :put, :delete.
   Returns {:status int :body parsed-json}."
  [base-url method path & {:keys [body timeout-ms]}]
  (let [url (str (str/replace base-url #"/$" "") path)
        opts (merge {:accept :json
                     :as :json
                     :throw-exceptions false}
                    (when timeout-ms {:socket-timeout timeout-ms
                                      :connection-timeout timeout-ms})
                    (when body {:headers {"Content-Type" "application/json"}
                                :body (json/generate-string body)}))
        response (case method
                   :get    (http/get url opts)
                   :post   (http/post url opts)
                   :put    (http/put url opts)
                   :delete (http/delete url opts))]
    {:status (:status response)
     :body (:body response)}))

(defn find-collection-by-name
  "Find a Pseudovision collection by exact name match.
   Returns the collection map with :id, :name, :kind, etc., or nil."
  [base-url name]
  (let [{:keys [status body]} (pv-request base-url :get "/api/media/collections" {:query-params {:limit 1000}})]
    (when (= 200 status)
      (->> (get body :items [])
           (filter #(= name (:name %)))
           first))))

(defn create-collection
  "Create a new manual collection in Pseudovision.
   Returns the created collection map with :id."
  [base-url name]
  (let [{:keys [status body]} (pv-request base-url :post "/api/media/collections"
                                           {:body {:name name :kind "manual"}})]
    (if (= 201 status)
      (do (log/info "Created Pseudovision collection" {:name name :id (:id body)})
          body)
      (do (log/error "Failed to create Pseudovision collection"
                     {:name name :status status :body body})
          (throw (ex-info "PV collection creation failed"
                          {:name name :status status :body body}))))))

(defn find-or-create-collection
  "Find a collection by name, or create it if it doesn't exist.
   Returns the collection map."
  [base-url name]
  (if-let [existing (find-collection-by-name base-url name)]
    (do (log/info "Found existing Pseudovision collection" {:name name :id (:id existing)})
        existing)
    (create-collection base-url name)))

(defn find-media-item-by-jellyfin-id
  "Find a Pseudovision media item by its Jellyfin remote-key.
   PV's GET /api/media/items/:id accepts remote_key as the id parameter.
   Returns the media item map, or nil."
  [base-url jellyfin-id]
  (let [{:keys [status body]} (pv-request base-url :get (str "/api/media/items/" jellyfin-id))]
    (when (= 200 status)
      body)))

(defn add-item-to-collection
  "Add a media item (by internal PV id) to a collection.
   Returns true on success."
  [base-url collection-id media-item-id]
  (let [{:keys [status]} (pv-request base-url :post
                                    (str "/api/media/collections/" collection-id "/items")
                                    {:body {:media-item-id media-item-id}})]
    (if (= 204 status)
      (do (log/info "Added item to collection"
                    {:collection-id collection-id :media-item-id media-item-id})
          true)
      (do (log/warn "Failed to add item to collection"
                    {:collection-id collection-id :media-item-id media-item-id :status status})
          false))))

(defn ensure-bumper-collection!
  "Ensure a 'Bumpers: <channel>' collection exists for the given channel name.
   Returns the collection map."
  [base-url channel-name]
  (find-or-create-collection base-url (str "Bumpers: " channel-name)))

(defn register-bumper!
  "Register a newly generated bumper in Pseudovision.
   1. Looks up the media item by Jellyfin ID
   2. Ensures the bumper collection exists
   3. Adds the item to the collection
   Returns true on success, false if the PV item isn't found yet."
  [base-url channel-name jellyfin-id]
  (if-let [item (find-media-item-by-jellyfin-id base-url jellyfin-id)]
    (let [collection (ensure-bumper-collection! base-url channel-name)
          item-id (:id item)]
      (add-item-to-collection base-url (:id collection) item-id))
    (do (log/warn "PV item not yet found for Jellyfin ID" {:jellyfin-id jellyfin-id})
        false)))
