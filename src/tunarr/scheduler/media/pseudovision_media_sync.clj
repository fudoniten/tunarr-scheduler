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
;; Media Item Classification
;; ---------------------------------------------------------------------------

(defn- classify-item-kind
  "Determine item_kind based on Pseudovision metadata structure.
  
   This replaces the strict episode number requirement with intelligent
   classification that allows YouTube/orphaned content to be treated as filler."
  [pv-item]
  (let [parent-id (:parent-id pv-item)
        season-number (:season-number pv-item)
        episode-number (or (:position pv-item) 
                          (:episode-number pv-item) 
                          (:index-number pv-item))
        kind (:kind pv-item)
        is-episode (= kind :episode)]
    
    (cond
      ;; Has parent relationship AND proper episode structure → episode
      (and parent-id is-episode season-number episode-number) :episode
      
      ;; Has season/episode structure but no parent → likely series entry
      (and season-number episode-number (not parent-id)) :series
      
      ;; Explicitly marked as show/series and no parent → series
      (and (#{:show :series} kind) (not parent-id)) :series
      
      ;; Movie type → movie
      (= kind :movie) :movie
      
      ;; Everything else → filler (YouTube, orphaned content, etc.)
      :else :filler)))

;; ---------------------------------------------------------------------------
;; Media Item Mapping
;; ---------------------------------------------------------------------------

(defn- pseudovision-item->catalog-item
  "Convert a Pseudovision media_item to tunarr-scheduler catalog format.

   Uses intelligent classification to determine item_kind, allowing filler
   content to bypass episode structure requirements. Preserves Jellyfin ID 
   mapping for tag sync."
  [pv-item catalog-library-id]
  (let [item-kind (classify-item-kind pv-item)
        item-type (case (:kind pv-item)
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
        ;; Episode numbers - only required for :episode kind, optional for filler
        season-number (when (= item-kind :episode)
                        (:season-number pv-item))
        episode-number (when (= item-kind :episode)
                         (or (:position pv-item)
                             (:episode-number pv-item)
                             (:index-number pv-item)))]
    (log/debug "Mapping PV item to catalog"
               {:pv-id (:id pv-item)
                :name (:name pv-item)
                :item-kind item-kind
                :item-type item-type
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
      ::media/item-kind    item-kind              ; NEW: Add item_kind classification
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

(defn- process-single-item
  "Process a single media item from Pseudovision.
  
   Returns a map with :synced, :skipped, :errors to accumulate results."
  [catalog pv-config item-stub catalog-lib-id idx report-progress page]
  (let [item (pv/get-media-item pv-config (:id item-stub))
        _ (when (< idx 5)
            (log/debug "Fetched PV item"
                       {:item-id (:id item-stub)
                        :item-keys (keys item)
                        :sample-data (select-keys item [:id :name :year :remote-key :kind :parent-id :release-date])}))
        catalog-item (pseudovision-item->catalog-item item catalog-lib-id)
        item-kind (::media/item-kind catalog-item)
        
        should-skip? (and (= :episode item-kind)
                          (or (nil? (::media/season-number catalog-item))
                              (nil? (::media/episode-number catalog-item))))
        
        _ (when should-skip?
            (log/warn "Skipping malformed episode missing season/episode numbers"
                      {:item-id (:id item-stub) 
                       :name (:name item)
                       :item-kind item-kind
                       :season (::media/season-number catalog-item)
                       :episode (::media/episode-number catalog-item)}))
        
        _ (when (and (= :filler item-kind) (< idx 3))
            (log/info "Ingesting filler content"
                      {:item-id (:id item-stub)
                       :name (:name item)
                       :item-kind item-kind}))
                       
        err (when-not should-skip?
              (try
                (catalog/add-media! catalog catalog-item)
                nil
                (catch Exception e
                  (log/warn e "Failed to sync item"
                            {:item-id (:id item-stub)
                             :item-keys (keys item)})
                  {:item-id (:id item-stub) :error (.getMessage e)})))]
    (report-progress {:phase "syncing" :page page :item idx})
    {:synced (if (or err should-skip?) 0 1)
     :skipped (if should-skip? 1 0)
     :errors (if (and err (not should-skip?)) [err] [])}))

(defn- process-item-batch
  "Process a batch of item stubs from Pseudovision.
  
   Returns a map with :synced, :skipped, :errors counts."
  [catalog pv-config item-stubs catalog-lib-id report-progress page]
  (loop [remaining item-stubs
         idx 0
         synced 0
         skipped 0
         errors []]
    (if (empty? remaining)
      {:synced synced :skipped skipped :errors errors}
      (let [stub (first remaining)
            result (process-single-item catalog pv-config stub catalog-lib-id idx report-progress page)]
        (recur (rest remaining)
               (inc idx)
               (+ synced (:synced result))
               (+ skipped (:skipped result))
               (concat errors (:errors result)))))))

(defn- fetch-and-process-pages
  "Fetch and process items from Pseudovision in paginated batches.
  
   Returns a map with :synced, :skipped, :errors counts."
  [catalog pv-config pv-library-id catalog-lib-id library report-progress]
  (let [batch-size 500
        attrs-str "id,remote-key,name,year,parent-id,position"]
    (log/info "Starting PV→TS sync with pagination"
              {:library library :batch-size batch-size})
    
    (loop [page 0
           total-synced 0
           total-skipped 0
           total-errors []]
      (let [offset (* page batch-size)
            item-stubs (pv/list-library-items pv-config pv-library-id 
                                              {:attrs attrs-str
                                               :limit batch-size
                                               :offset offset})]
        
        (if (empty? item-stubs)
          ;; Pagination complete: no more items returned
          (do
            (log/info "Pseudovision media sync complete"
                      {:library library 
                       :total-synced total-synced 
                       :total-skipped total-skipped 
                       :total-errors-count (count total-errors)})
            (when (seq total-errors)
              (log/debug "Sample sync errors" {:first-errors (take 3 total-errors)}))
            {:synced total-synced :skipped total-skipped :errors total-errors})
          
          ;; Process this batch of items
          (do
            (log/debug "Fetching page"
                       {:page page :offset offset :batch-size (count item-stubs)})
            (report-progress {:phase "fetching" :page page :offset offset :items-in-batch (count item-stubs)})
            
            (let [batch-result (process-item-batch catalog pv-config item-stubs 
                                                   catalog-lib-id report-progress page)]
              ;; Accumulate results from this batch and fetch next page
              (recur (inc page)
                     (+ total-synced (:synced batch-result))
                     (+ total-skipped (:skipped batch-result))
                     (concat total-errors (:errors batch-result))))))))))

(defn- normalize-library-name
  "Normalize library name for lookup (convert hyphens to spaces)."
  [library]
  (if (string? library)
    (clojure.string/replace library #"-" " ")
    library))

(defn- get-catalog-library-id
  "Get catalog library ID, trying both normalized and original names."
  [catalog library]
  (let [normalized-lib (normalize-library-name library)]
    (or (catalog/get-library-id catalog normalized-lib)
        (catalog/get-library-id catalog library))))

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
        normalized-lib (normalize-library-name library)]
    (log/info "Syncing media FROM Pseudovision" {:library library :normalized normalized-lib})
    
    (try
      ;; Get catalog library-id by querying the database
      (let [catalog-lib-id (get-catalog-library-id catalog library)
            _ (log/info "Retrieved catalog library ID"
                        {:library library :normalized normalized-lib :catalog-lib-id catalog-lib-id})
            _ (when-not catalog-lib-id
                (throw (ex-info "Library not found in catalog"
                                {:library library :normalized normalized-lib})))
            
            ;; Use catalog-lib-id as Pseudovision library ID
            ;; (they're synchronized during library sync from Pseudovision)
            pv-library-id catalog-lib-id]
        
        (log/info "Fetching items from Pseudovision library"
                  {:pv-library-id pv-library-id :catalog-lib-id catalog-lib-id})
        
        (fetch-and-process-pages catalog pv-config pv-library-id catalog-lib-id 
                                 library report-progress))
      (catch Exception e
        (log/error e "Failed to sync from Pseudovision")
        {:synced 0 :skipped 0 :errors [{:error (.getMessage e)}]}))))

;; ---------------------------------------------------------------------------
;; Migration Helper
;; ---------------------------------------------------------------------------

(defn- migrate-single-item
  "Migrate a single catalog item to use Pseudovision ID.
  
   Returns a map with :migrated (0 or 1), :skipped (0 or 1), and :error (or nil)."
  [catalog pv-config item]
  (let [jf-id (:jellyfin-id item)]
    (if-not jf-id
      {:migrated 0 :skipped 1 :error nil}
      (try
        (let [pv-item (pv/find-media-item-by-jellyfin-id pv-config jf-id)]
          (if pv-item
            (do
              (catalog/add-media! catalog (assoc item :id (:id pv-item)))
              {:migrated 1 :skipped 0 :error nil})
            (do
              (log/warn "No Pseudovision item found for Jellyfin ID"
                        {:jellyfin-id jf-id :title (:title item)})
              {:migrated 0 :skipped 1 :error nil})))
        (catch Exception e
          (log/error e "Migration failed for item" {:jellyfin-id jf-id})
          {:migrated 0 :skipped 0 :error {:jellyfin-id jf-id :error (.getMessage e)}})))))

(defn- migrate-items-loop
  "Loop through catalog items and migrate each one.
  
   Returns a map with :migrated, :skipped, :errors counts."
  [catalog pv-config catalog-items]
  (loop [remaining catalog-items
         migrated 0
         skipped 0
         errors []]
    (if (empty? remaining)
      {:migrated migrated :skipped skipped :errors errors}
      (let [item (first remaining)
            result (migrate-single-item catalog pv-config item)]
        (recur (rest remaining)
               (+ migrated (:migrated result))
               (+ skipped (:skipped result))
               (if (:error result)
                 (conj errors (:error result))
                 errors))))))

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
          total (count catalog-items)]
      (log/info "Building Pseudovision item index for migration" {:items total})
      
      (let [result (migrate-items-loop catalog pv-config catalog-items)]
        (log/info "Migration complete" 
                  {:library library 
                   :migrated (:migrated result) 
                   :skipped (:skipped result)})
        result))
    (catch Exception e
      (log/error e "Migration failed")
      {:migrated 0 :skipped 0 :errors [{:error (.getMessage e)}]})))

(comment
  ;; Usage example:

  ;; One-time: Migrate existing catalog
  (migrate-catalog-to-pseudovision! catalog pv-config :movies)

  ;; Future: Sync from Pseudovision instead of Jellyfin
  (sync-library-from-pseudovision! catalog pv-config :movies {}))
