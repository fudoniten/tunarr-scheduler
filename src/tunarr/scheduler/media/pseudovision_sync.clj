(ns tunarr.scheduler.media.pseudovision-sync
  "Sync catalog tags and metadata to Pseudovision.

   Tags are sourced from two places:
   1. The free-form `media_tags` table (base tags).
   2. The dimension-based `media_categorization` table, which is flattened
      into prefixed strings like `dimension:value` before pushing.

   This replaces the old hardcoded derivation from `genres`, `channels`,
   and `kid_friendly` fields — those are now just dimensions like any other.

   The sync is a **reconcile**, not a merge: `sync-item-tags!` computes
   the diff between the catalog's desired tag set and Pseudovision's
   current set, then `add-tags!` for new entries and `delete-tag!` for
   stale ones. Without the delete leg, recategorizations would leave
   obsolete `dim:value` strings (e.g. a previous `channel:goldenreels`)
   behind in PV, where smart collections match on those tags and pull
   the show into the wrong channel."
  (:require [tunarr.scheduler.backends.pseudovision.client :as pv]
            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.media.catalog :as catalog]
            [clojure.set :as set]
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
  "Reconcile tags for a single media item between the catalog (TS) and
   Pseudovision. Computes the desired tag set from the catalog's
   `media_tags` and `media_categorization` (flattened to `dim:value`
   strings), fetches the current set from Pseudovision, and applies a
   **diff**: `add-tags!` for new entries, `delete-tag!` for entries
   that are no longer in the catalog. This is what keeps the two
   stores aligned across recategorizations — without it, a previous
   value (e.g. `channel:goldenreels` from an earlier LLM
   categorization) lingers in PV and pulls the show into the wrong
   smart collection.

   The delete path runs even when the desired set is empty, so a
   fully-untagged item in TS clears its PV tags too. Adds and deletes
   are sorted for deterministic logs; the result map reports what
   changed so callers and the audit endpoint can show the diff.

   Args:
     pv-config - Pseudovision client config
     pv-item-id - Pseudovision media_items.id
     catalog - Catalog instance
     item - catalog media item (must carry `::media/id`)

   Returns:
     {:synced true/false
      :tags [... desired ...]
      :added [... added ...]
      :removed [... removed ...]
      :unchanged int
      :error ...}"
  [pv-config pv-item-id catalog item]
  (try
    (let [;; Desired tag set: base tags from media_tags + flattened
          ;; dimension tags from media_categorization. Both
          ;; `(name nil)` and `(name "")` are safe in Clojure, but we
          ;; still filter out nils/empties via `clean-tags` below so
          ;; the result is always presentable to the PV API.
          base-tags (catalog/get-media-tags catalog (::media/id item))
          dimension-tags
          (mapcat (fn [[dim values]]
                    (map #(str (name dim) ":" (name %)) values))
                  (catalog/get-media-categories catalog (::media/id item)))
          all-tags (vec (distinct (concat (map #(some-> % name) base-tags)
                                          dimension-tags)))
          clean-tags (filterv (fn [t] (and (string? t) (seq t))) all-tags)
          desired-set (set clean-tags)
          ;; Current tag set in PV. A failed GET (e.g. item not yet in
          ;; PV, or PV 404) is treated as an empty set so the add path
          ;; runs and the item is bootstrapped.
          current-set (try (set (pv/get-tags pv-config pv-item-id))
                           (catch Exception e
                             (log/warn e "Failed to fetch current tags from PV; treating as empty"
                                       {:pv-item-id pv-item-id})
                             #{}))
          to-add    (vec (sort (set/difference desired-set current-set)))
          to-remove (vec (sort (set/difference current-set desired-set)))
          unchanged (count (set/intersection desired-set current-set))]
      (log/info "Reconciling PV tags"
                {:pv-item-id pv-item-id
                 :desired    clean-tags
                 :current    (vec (sort current-set))
                 :added      to-add
                 :removed    to-remove
                 :unchanged  unchanged})
      (when (seq to-remove)
        (doseq [tag to-remove]
          (pv/delete-tag! pv-config pv-item-id tag)))
      (when (seq to-add)
        (pv/add-tags! pv-config pv-item-id to-add))
      {:synced    true
       :tags      clean-tags
       :added     to-add
       :removed   to-remove
       :unchanged unchanged})
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
