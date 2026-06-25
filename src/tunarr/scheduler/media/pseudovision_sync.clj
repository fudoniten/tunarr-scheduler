(ns tunarr.scheduler.media.pseudovision-sync
  "Sync catalog tags and metadata to Pseudovision.
   
   Replaces the Jellyfin tag sync - instead of pushing tags to Jellyfin
   (for ErsatzTV to read), we now push directly to Pseudovision's tag API."
  (:require [tunarr.scheduler.backends.pseudovision.client :as pv]
            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.media.catalog :as catalog]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Jellyfin ID Mapping
;; ---------------------------------------------------------------------------

(defn- build-jellyfin-id-map
  "Build a map of Jellyfin ID → Pseudovision media_item for fast lookup.

   Lists all libraries and their items, requesting the remote-key attribute
   so each item can be matched back to its Jellyfin ID.

   Returns map: {jellyfin-id → {:id media-item-id, ...}}"
  [pv-config]
  (log/info "Building Jellyfin ID → Pseudovision media_item mapping")
  (try
    (let [libraries (pv/list-all-libraries pv-config)]
      (log/info "Scanning libraries for Jellyfin ID mapping" {:count (count libraries)})
      (reduce
        (fn [acc lib]
          (try
            (let [items (pv/list-library-items pv-config (:id lib) {:attrs "remote-key"})]
              (reduce (fn [m item]
                        (if-let [jf-id (:remote-key item)]
                          (assoc m (str jf-id) item)
                          m))
                      acc
                      items))
            (catch Exception e
              (log/warn e "Failed to list items for library" {:library-id (:id lib)})
              acc)))
        {}
        libraries))
    (catch Exception e
      (log/error e "Failed to build Jellyfin ID map")
      {})))

;; ---------------------------------------------------------------------------
;; Tag Sync
;; ---------------------------------------------------------------------------

(defn sync-item-tags!
  "Sync tags for a single media item from catalog to Pseudovision.
   
   Args:
     pv-config - Pseudovision client config
     pv-item-id - Pseudovision media_items.id
     catalog - Catalog instance
     catalog-item-id - Item ID in catalog
   
   Returns:
     {:synced true/false, :tags [...], :error ...}"
  [pv-config pv-item-id catalog item]
  (try
    (let [;; Base tags from catalog
          base-tags (catalog/get-media-tags catalog (::media/id item))
          ;; Derived tags from first-class fields
          genre-tags (map #(str "genre:" (name %)) (::media/genres item))
          channel-tags (map #(str "channel:" (name %)) (::media/channel-names item))
          kid-tag (when (::media/kid-friendly? item) ["kid-friendly"])
          ;; Merge all tags (deduplicated)
          all-tags (vec (distinct (concat (map name base-tags)
                                          genre-tags
                                          channel-tags
                                          kid-tag)))]
      (if (seq all-tags)
        (do
          (pv/add-tags! pv-config pv-item-id all-tags)
          (log/debug "Synced tags to Pseudovision"
                    {:pv-item-id pv-item-id
                     :tags all-tags})
          {:synced true :tags all-tags})
        (do
          (log/debug "No tags to sync" {:pv-item-id pv-item-id})
          {:synced false :tags []})))
    (catch Exception e
      (log/error e "Failed to sync tags" {:pv-item-id pv-item-id})
      {:synced false :error (.getMessage e)})))

(defn- sync-library-item!
  "Sync tags for one catalog item, resolving it through the Jellyfin ID map.

   Returns {:synced? bool :error error-map-or-nil}."
  [catalog pv-config id-map item]
  (let [jf-id (::media/id item)]
    (if-let [pv-item (get id-map jf-id)]
      (let [result (sync-item-tags! pv-config (:id pv-item) catalog item)]
        {:synced? (boolean (:synced result))
         :error (when (:error result)
                  {:jellyfin-id jf-id :error (:error result)})})
      (do
        (log/warn "No Pseudovision item found for Jellyfin ID"
                  {:jellyfin-id jf-id :title (::media/name item)})
        {:synced? false
         :error {:jellyfin-id jf-id :error "No matching Pseudovision media item"}}))))

(defn sync-library-tags!
  "Sync all tags from catalog to Pseudovision for a library.

   This is the main entry point called by the API endpoint.

   Args:
     catalog - Catalog instance with tagged media
     pv-config - Pseudovision client config
     library - Library name (keyword like :movies)
     opts - Options map with :report-progress function

   Returns:
     Map with :synced, :failed, :errors counts"
  [catalog pv-config library opts]
  (let [report-progress (get opts :report-progress (constantly nil))
        items (catalog/get-media-by-library catalog library)
        total (count items)
        id-map (build-jellyfin-id-map pv-config)]
    (log/info "Starting Pseudovision tag sync"
              {:library library :items total})
    (report-progress {:phase "mapping" :completed 0 :total total})
    (let [totals (reduce
                  (fn [totals [idx item]]
                    (report-progress {:phase "syncing" :completed idx :total total
                                      :failed (:failed totals)
                                      :current-item {:id   (::media/id item)
                                                     :name (::media/name item)}})
                    (let [{:keys [synced? error]} (sync-library-item! catalog pv-config id-map item)]
                      (cond-> totals
                        synced? (update :synced inc)
                        error   (-> (update :failed inc)
                                    (update :errors conj error)))))
                  {:synced 0 :failed 0 :errors []}
                  (map-indexed vector items))]
      (report-progress {:phase "syncing" :completed total :total total
                        :failed (:failed totals)})
      (log/info "Pseudovision tag sync complete"
                {:library library
                 :synced (:synced totals)
                 :failed (:failed totals)})
      totals)))

;; ---------------------------------------------------------------------------
;; Convenience Wrappers
;; ---------------------------------------------------------------------------

(defn sync-all-libraries!
  "Sync tags for all configured libraries to Pseudovision.
   
   Returns map of library → sync results"
  [catalog pv-config libraries opts]
  (reduce
    (fn [results library]
      (log/info "Syncing library to Pseudovision" {:library library})
      (let [result (sync-library-tags! catalog pv-config library opts)]
        (assoc results library result)))
    {}
    libraries))
