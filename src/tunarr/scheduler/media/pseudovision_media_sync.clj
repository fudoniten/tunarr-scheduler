(ns tunarr.scheduler.media.pseudovision-media-sync
  "Sync media items FROM Pseudovision TO tunarr-scheduler catalog.

   This replaces the Jellyfin → tunarr-scheduler sync with
   Pseudovision → tunarr-scheduler sync, making Pseudovision the
   single source of truth for media discovery.

   Workflow:
   1. Pseudovision scans Jellyfin libraries (separate process)
   2. tunarr-scheduler pulls media items from Pseudovision API
   3. tunarr-scheduler adds LLM categorization/tags to catalog
   4. tunarr-scheduler pushes tags back to Pseudovision

   Benefits:
   - Single media discovery pipeline (no duplicate Jellyfin scans)
   - Pseudovision owns media metadata
   - tunarr-scheduler focuses on curation only"
  (:require [tunarr.scheduler.backends.pseudovision.client :as pv]
            [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.media :as media]
            [clojure.string]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Media Item Mapping
;; ---------------------------------------------------------------------------

(defn- pseudovision-item->catalog-item
  "Convert a Pseudovision media_item to tunarr-scheduler catalog format.

   Preserves Jellyfin ID mapping for tag sync."
  [pv-item catalog-library-id]
  (let [item-type (case (:kind pv-item)
                    :show   :series      ; Map PV \"show\" to TS \"series\"
                    :episode :episode    ; Keep as-is
                    :season  :season     ; Keep as-is
                    :movie   :movie      ; Keep as-is
                    :song    :song       ; Keep as-is
                    :music_video :music-video  ; Map PV \"music_video\" to TS \"music-video\"
                    :image   :image      ; Keep as-is
                    (keyword (:kind pv-item :movie)))  ; Default to :movie if kind missing
        year (or (:year pv-item) 1970)  ; Default to 1970 if year is missing
        ;; Premiere is required - use release-date if available, else construct from year
        ;; Convert to LocalDate for proper SQL DATE type (needs format: \"YYYY-MM-DD\")
        premiere-date-str (or (:release-date pv-item)
                              (str year))
        premiere (if (= 4 (count premiere-date-str))
                   (java.time.LocalDate/of year 1 1)  ; Construct date from year
                   (java.time.LocalDate/parse premiere-date-str))
        ;; Episode numbers are required for episodes - get from position or index
        season-number (when (= item-type :episode)
                        (:season-number pv-item))
        episode-number (when (= item-type :episode)
                         (or (:position pv-item)
                             (:episode-number pv-item)
                             (:index-number pv-item)))]
    (log/debug "Mapping PV item to catalog"
               {:pv-id (:id pv-item)
                :name (:name pv-item)
                :year year
                :release-date (:release-date pv-item)
                :premiere premiere
                :has-year (contains? pv-item :year)
                :has-name (contains? pv-item :name)
                :has-kind (contains? pv-item :kind)
                :has-release-date (contains? pv-item :release-date)
                :all-keys (keys pv-item)
                :season-number season-number
                :episode-number episode-number})
    (merge
     {::media/id           (:remote-key pv-item)  ; Use Jellyfin ID as catalog ID
      ::media/name         (:name pv-item)
      ::media/type         item-type
      ::media/library-id   catalog-library-id    ; TS catalog library ID
      ::media/parent-id    (:parent-id pv-item)
      ::media/production-year year
      ::media/premiere     premiere}
     (when (and season-number episode-number)
       {::media/season-number season-number
        ::media/episode-number episode-number}))))

(defn- library-kind->catalog-library
  "Map Pseudovision library kind to tunarr-scheduler library keyword."
  [kind]
  (case kind
    "movies"       :movies
    "shows"        :shows
    "music_videos" :music-videos
    "other_videos" :other-videos
    "songs"        :songs
    "images"       :images
    (keyword kind)))

;; ---------------------------------------------------------------------------
;; Sync FROM Pseudovision
;; ---------------------------------------------------------------------------

(defn sync-library-from-pseudovision!
  "Sync media items from Pseudovision into tunarr-scheduler catalog.

   This REPLACES the Jellyfin sync - instead of querying Jellyfin directly,
   we pull from Pseudovision which has already scanned Jellyfin.

   Args:
     catalog - Catalog instance to update
     pv-config - Pseudovision client config
     library - Library name (string like \"youtube-filler\" or \"YouTube Filler\") or integer library ID
     opts - Options with :report-progress

   Returns:
     Map with :synced, :skipped, :errors counts

   Note: Existing tags in catalog are PRESERVED - we only update media
   metadata, not categorization data."
  [catalog pv-config library opts]
  (let [report-progress (get opts :report-progress (constantly nil))
        ;; Normalize library name for lookup:
        ;; - Convert hyphens to spaces: "youtube-filler" -> "youtube filler"
        ;; - Then try both as-is and with proper casing
        normalized-lib (if (string? library)
                        (clojure.string/replace library #"-" " ")
                        library)]

    (log/info "Syncing media FROM Pseudovision" {:library library :normalized normalized-lib})

    (try
      ;; Get catalog library-id by querying the database
      (let [catalog-lib-id (or (catalog/get-library-id catalog normalized-lib)
                               (catalog/get-library-id catalog library))  ; Try original if normalized fails
            _ (log/info "Retrieved catalog library ID"
                        {:library library :normalized normalized-lib :catalog-lib-id catalog-lib-id})
            _ (when-not catalog-lib-id
                (throw (ex-info "Library not found in catalog "
                                {:library library :normalized normalized-lib})))

            ;; Use catalog-lib-id as Pseudovision library ID
            ;; (they're synchronized during library sync from Pseudovision)
            pv-library-id catalog-lib-id]

        (log/info "Fetching items from Pseudovision library"
                  {:pv-library-id pv-library-id :catalog-lib-id catalog-lib-id})

        (let [item-stubs (pv/list-library-items pv-config pv-library-id {:attrs "id,remote-key,name,year,parent-id,position"})
              total      (count item-stubs)]

          (log/info "Starting PV→TS sync"
                    {:library library
                     :total-items total
                     :sample-stub (first item-stubs)})

          (report-progress {:phase "fetching" :current 0 :total total})

          (loop [remaining item-stubs
                 idx    0
                 synced 0
                 skipped 0
                 errors []]
            (if (empty? remaining)
              (do
                (log/info "Pseudovision media sync complete"
                          {:library library :synced synced :skipped skipped :errors-count (count errors)})
                (when (seq errors)
                  (log/debug "Sample sync errors" {:first-errors (take 3 errors)}))
                {:synced synced :skipped skipped :errors errors})

              (let [stub (first remaining)
                    item (pv/get-media-item pv-config (:id stub))
                    _ (when (< idx 5)  ; Log first 5 items for debugging
                        (log/debug "Fetched PV item"
                                   {:item-id (:id stub)
                                    :item-keys (keys item)
                                    :sample-data (select-keys item [:id :name :year :remote-key :kind :parent-id :release-date])}))
                    catalog-item (pseudovision-item->catalog-item item catalog-lib-id)
                    episode-missing-numbers? (and (= :episode (::media/type catalog-item))
                                                  (or (nil? (::media/season-number catalog-item))
                                                      (nil? (::media/episode-number catalog-item))))
                    _ (when episode-missing-numbers?
                        (log/warn "Skipping episode missing season/episode numbers"
                                  {:item-id (:id stub) :name (:name item)}))
                    err  (when-not episode-missing-numbers?
                           (try
                             (catalog/add-media! catalog catalog-item)
                             (catch Exception e
                               (log/warn e "Failed to sync item"
                                         {:item-id (:id stub)
                                          :item-keys (keys item)})
                               {:item-id (:id stub) :error (.getMessage e)})))]
                (report-progress {:phase "syncing" :current (inc idx) :total total})
                (recur (rest remaining)
                       (inc idx)
                       (if (or err episode-missing-numbers?) synced (inc synced))
                       (if episode-missing-numbers? (inc skipped) skipped)
                       (if (and err (not episode-missing-numbers?)) (conj errors err) errors)))))))

      (catch Exception e
        (log/error e "Failed to sync from Pseudovision")
        {:synced 0 :skipped 0 :errors [{:error (.getMessage e)}]}))))

;; ---------------------------------------------------------------------------
;; Migration Helper
;; ---------------------------------------------------------------------------

(defn migrate-catalog-to-pseudovision!
  "One-time migration: Match existing catalog items to Pseudovision by Jellyfin ID.

   This preserves all LLM tags and categorization while switching the
   sync source from Jellyfin to Pseudovision.

   Process:
   1. For each item in tunarr-scheduler catalog
   2. Find matching Pseudovision media_item by remote_key (Jellyfin ID)
   3. Update catalog item's :id to Pseudovision media_item.id
   4. Preserve all tags and categories

   After migration, future syncs use Pseudovision as source."
  [catalog pv-config library]
  (log/info "Migrating catalog to use Pseudovision IDs" {:library library})

  (try
    (let [catalog-items (catalog/get-media-by-library catalog library)
          total         (count catalog-items)]

      (log/info "Building Pseudovision item index for migration" {:items total})

      (loop [remaining catalog-items
             migrated 0
             skipped  0
             errors   []]
        (if (empty? remaining)
          (do
            (log/info "Migration complete" {:library library :migrated migrated :skipped skipped})
            {:migrated migrated :skipped skipped :errors errors})

          (let [item  (first remaining)
                jf-id (:jellyfin-id item)]
            (if-not jf-id
              (recur (rest remaining) migrated (inc skipped) errors)
              (let [[found? err] (try
                                   (let [pv-item (pv/find-media-item-by-jellyfin-id pv-config jf-id)]
                                     (if pv-item
                                       (do (catalog/add-media! catalog (assoc item :id (:id pv-item)))
                                           [true nil])
                                       (do (log/warn "No Pseudovision item found for Jellyfin ID"
                                                     {:jellyfin-id jf-id :title (:title item)})
                                           [false nil])))
                                   (catch Exception e
                                     (log/error e "Migration failed for item" {:jellyfin-id jf-id})
                                     [false {:jellyfin-id jf-id :error (.getMessage e)}]))]
                (recur (rest remaining)
                       (if found? (inc migrated) migrated)
                       (if (and (not found?) (nil? err)) (inc skipped) skipped)
                       (if err (conj errors err) errors))))))))
    (catch Exception e
      (log/error e "Migration failed")
      {:migrated 0 :skipped 0 :errors [{:error (.getMessage e)}]})))

(comment
  ;; Usage example:

  ;; One-time: Migrate existing catalog
  (migrate-catalog-to-pseudovision! catalog pv-config :movies)

  ;; Future: Sync from Pseudovision instead of Jellyfin
  (sync-library-from-pseudovision! catalog pv-config :movies {}))
