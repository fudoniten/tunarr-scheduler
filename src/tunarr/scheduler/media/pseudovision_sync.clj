(ns tunarr.scheduler.media.pseudovision-sync
  "Sync catalog tags and metadata to Pseudovision.

   Tags are sourced from two places:
   1. The free-form `media_tags` table (base tags).
   2. The dimension-based `media_categorization` table, which is flattened
      into prefixed strings like `dimension:value` before pushing.

   This replaces the old hardcoded derivation from `genres`, `channels`,
   and `kid_friendly` fields — those are now just dimensions like any other."
  (:require [tunarr.scheduler.backends.pseudovision.client :as pv]
            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.media.catalog :as catalog]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Jellyfin ID Mapping
;; ---------------------------------------------------------------------------

(defn build-jellyfin-id-map
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
    (let [;; Base tags from the free-form media_tags table
          base-tags (catalog/get-media-tags catalog (::media/id item))
          ;; Dimension values from media_categorization, flattened to
          ;; prefixed strings: {:channel [:comedy]} -> ["channel:comedy"]
          dimension-tags
          (mapcat (fn [[dim values]]
                    (map #(str (name dim) ":" (name %)) values))
                  (catalog/get-media-categories catalog (::media/id item)))
          ;; Merge all tags (deduplicated)
          all-tags (vec (distinct (concat (map name base-tags)
                                          dimension-tags)))
          ;; Clean: remove nils and empty strings that would fail TagName schema
          clean-tags (filterv (fn [t] (and (string? t) (seq t))) all-tags)]
      (if (seq clean-tags)
        (do
          (log/info "Syncing tags to Pseudovision"
                    {:pv-item-id pv-item-id
                     :tags clean-tags
                     :all-tags all-tags})
          (pv/add-tags! pv-config pv-item-id clean-tags)
          (log/debug "Synced tags to Pseudovision"
                    {:pv-item-id pv-item-id
                     :tags clean-tags})
          {:synced true :tags clean-tags})
        (do
          (log/debug "No tags to sync" {:pv-item-id pv-item-id})
          {:synced false :tags []})))
    (catch Exception e
      (log/error e "Failed to sync tags" {:pv-item-id pv-item-id})
      {:synced false :error (.getMessage e)})))

(defn sync-item!
  "Sync a single catalog item to Pseudovision, resolving the Pseudovision
   media-item id via the API (rather than a prebuilt id-map).

   This is the cheap path for one-off/interactive syncs: it costs a single
   `get-media-item` lookup and avoids the full library scan that
   `build-jellyfin-id-map` performs. Mirrors the single-item HTTP endpoint.

   Returns {:synced true/false, :tags [...], :error ...}."
  [pv-config catalog item]
  (let [catalog-id (::media/id item)
        pv-item    (try (pv/get-media-item pv-config catalog-id)
                        (catch Exception _ nil))
        pv-item-id (or (:id pv-item) catalog-id)]
    (sync-item-tags! pv-config pv-item-id catalog item)))

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

(defn sync-items!
  "Sync a collection of catalog items to Pseudovision using a prebuilt
   Jellyfin ID → Pseudovision item map. Building the id-map once and reusing
   it here is what makes bulk syncs cheap; callers with a single item should
   prefer `sync-item!` instead.

   Args:
     catalog   - Catalog instance
     pv-config - Pseudovision client config
     id-map    - {jellyfin-id → pv-item} from `build-jellyfin-id-map`
     items     - seq of catalog media items
     opts      - options map with optional :report-progress function

   Returns a map with :synced, :failed, :errors counts."
  [catalog pv-config id-map items opts]
  (let [report-progress (get opts :report-progress (constantly nil))
        total (count items)
        totals (reduce
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
    totals))

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
    (let [totals (sync-items! catalog pv-config id-map items opts)]
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
