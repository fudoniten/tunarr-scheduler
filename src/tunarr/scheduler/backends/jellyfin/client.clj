(ns tunarr.scheduler.backends.jellyfin.client
  "Minimal Jellyfin API client for bumper orchestration."
  ;; Only implements what the bumper pipeline needs:
  ;; - Trigger library scans
  ;; - Query items by path/name to resolve Jellyfin IDs
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

(defn- jellyfin-get
  "Authenticated GET against Jellyfin."
  [base-url api-key path & {:as opts}]
  (let [url (str (str/replace base-url #"/$" "") path)
        req (merge {:accept :json
                    :as :json
                    :throw-exceptions false
                    :headers {"X-Emby-Authorization" (str "MediaBrowser Client=TunarrScheduler, Device=TunarrScheduler, DeviceId=TunarrScheduler, Version=1.0.0, Token=" api-key)
                               "X-MediaBrowser-Token" api-key}}
                   opts)]
    (log/debug "Jellyfin GET" {:url url})
    (http/get url req)))

(defn- jellyfin-post
  "Authenticated POST against Jellyfin."
  [base-url api-key path body & {:as opts}]
  (let [url (str (str/replace base-url #"/$" "") path)
        req (merge {:accept :json
                    :as :json
                    :throw-exceptions false
                    :headers {"Content-Type" "application/json"
                              "X-Emby-Authorization" (str "MediaBrowser Client=TunarrScheduler, Device=TunarrScheduler, DeviceId=TunarrScheduler, Version=1.0.0, Token=" api-key)
                              "X-MediaBrowser-Token" api-key}
                    :body (json/generate-string body)}
                   opts)]
    (log/debug "Jellyfin POST" {:url url :body body})
    (http/post url req)))

(defn list-libraries
  "Fetch Jellyfin virtual folders (libraries). Returns seq of library maps
   with :Name, :ItemId, :CollectionType, :Locations."
  [base-url api-key]
  (let [{:keys [status body]} (jellyfin-get base-url api-key "/Library/VirtualFolders")]
    (if (= 200 status)
      (do (log/info "Fetched Jellyfin libraries" {:count (count body)})
          body)
      (do (log/error "Failed to fetch Jellyfin libraries" {:status status :body body})
          (throw (ex-info "Jellyfin library fetch failed" {:status status :body body}))))))

(defn find-library-by-name
  "Return the library map whose :Name matches `library-name`, or nil."
  [base-url api-key library-name]
  (->> (list-libraries base-url api-key)
       (filter #(= library-name (:Name %)))
       first))

(defn trigger-library-scan
  "Trigger a full library refresh for the given library name.
   Returns true on success."
  [base-url api-key library-name]
  (let [library (find-library-by-name base-url api-key library-name)]
    (if-not library
      (do (log/warn "Jellyfin library not found, cannot trigger scan" {:name library-name})
          false)
      (let [library-id (:ItemId library)
            {:keys [status body]} (jellyfin-post base-url api-key
                                                  (str "/Items/" library-id "/Refresh")
                                                  {:Recursive true
                                                   :ImageRefreshMode "Default"
                                                   :MetadataRefreshMode "Default"})]
        (if (= 204 status)
          (do (log/info "Triggered Jellyfin library scan" {:library library-name :id library-id})
              true)
          (do (log/error "Jellyfin scan trigger failed" {:status status :body body})
              false))))))

(defn- strip-ext
  "Drop a trailing file extension (e.g. \"foo.mp4\" -> \"foo\")."
  [s]
  (str/replace (or s "") #"\.[^./]+$" ""))

(defn find-item-by-name
  "Search for an item in a specific library by its Name field.
   Returns the first matching item map, or nil.

   Jellyfin stores video items with the file extension stripped from :Name, so
   we search on (and fall back to matching) the extension-less basename while
   still preferring an exact match when one exists."
  [base-url api-key library-id item-name]
  (let [search (strip-ext item-name)
        {:keys [status body]} (jellyfin-get base-url api-key "/Items"
                                            {:query-params
                                             {:ParentId library-id
                                              :SearchTerm search
                                              :Limit 10
                                              :Fields "Id,Name,Path"}})]
    (when (= 200 status)
      (let [items (get body :Items [])]
        (or (first (filter #(= item-name (:Name %)) items))
            (first (filter #(= search (strip-ext (:Name %))) items)))))))

(defn create-library
  "Create a new Jellyfin library (virtual folder).
   `library-type` is one of: movies, tvshows, music, musicvideos, homevideos, boxsets.
   `paths` is a vector of filesystem paths Jellyfin should scan.
   Returns the created library map or throws on failure."
  [base-url api-key library-name library-type paths]
  (let [{:keys [status body]} (jellyfin-post base-url api-key
                                              "/Library/VirtualFolders"
                                              {:Name library-name
                                               :CollectionType library-type
                                               :Paths paths
                                               :LibraryOptions {}})]
    (if (= 200 status)
      (do (log/info "Created Jellyfin library" {:name library-name :type library-type :paths paths})
          body)
      (do (log/error "Failed to create Jellyfin library" {:status status :body body})
          (throw (ex-info "Jellyfin library creation failed"
                          {:status status :body body :name library-name}))))))

(defn create!
  "Create a Jellyfin client config map from env vars or explicit params.
   Reads JELLYFIN_URL and JELLYFIN_API_KEY from the environment."
  [{:keys [base-url api-key]}]
  (let [url (or base-url (System/getenv "JELLYFIN_URL") "http://jellyfin.arr.svc.cluster.local:8096")
        key (or api-key (System/getenv "JELLYFIN_API_KEY"))]
    (when-not key
      (log/warn "No Jellyfin API key configured — bumper→Jellyfin integration disabled"))
    {:base-url url :api-key key}))
