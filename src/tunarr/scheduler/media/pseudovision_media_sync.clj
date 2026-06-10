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

(def ^:private fetch-batch-size
  "Items requested per Pseudovision list call."
  500)

(def ^:private insert-batch-size
  "Catalog items inserted per add-media-batch! call."
  50)

(def ^:private item-stub-attrs "id,remote-key,name,year,parent-id,position")

(defn- normalize-library-name
  "Convert hyphens to spaces so \"youtube-filler\" matches \"youtube filler\".
   Non-string libraries (integer IDs) pass through unchanged."
  [library]
  (if (string? library)
    (clojure.string/replace library #"-" " ")
    library))

(defn- resolve-catalog-library-id
  "Look up the catalog library ID, trying the normalized name first and
   falling back to the original. Throws if neither matches.

   The returned ID doubles as the Pseudovision library ID - they're
   synchronized during library sync from Pseudovision."
  [catalog library normalized-lib]
  (let [catalog-lib-id (or (catalog/get-library-id catalog normalized-lib)
                           (catalog/get-library-id catalog library))]
    (log/info "Retrieved catalog library ID"
              {:library library :normalized normalized-lib :catalog-lib-id catalog-lib-id})
    (or catalog-lib-id
        (throw (ex-info "Library not found in catalog"
                        {:library library :normalized normalized-lib})))))

(defn- malformed-episode?
  "Episodes must carry season and episode numbers to be schedulable."
  [catalog-item]
  (and (= :episode (::media/item-kind catalog-item))
       (or (nil? (::media/season-number catalog-item))
           (nil? (::media/episode-number catalog-item)))))

(defn- fetch-catalog-item
  "Fetch the full Pseudovision item for a stub and convert it to catalog
   format. Returns {:stub stub :catalog-item item :skip? bool}, with :skip?
   set for malformed episodes. Logs samples from the start of each page."
  [pv-config catalog-lib-id stub idx]
  (let [item (pv/get-media-item pv-config (:id stub))
        catalog-item (pseudovision-item->catalog-item item catalog-lib-id)
        item-kind (::media/item-kind catalog-item)
        skip? (malformed-episode? catalog-item)]
    (when (< idx 5)
      (log/debug "Fetched PV item"
                 {:item-id (:id stub)
                  :item-keys (keys item)
                  :sample-data (select-keys item [:id :name :year :remote-key :kind :parent-id :release-date])}))
    (when skip?
      (log/warn "Skipping malformed episode missing season/episode numbers"
                {:item-id (:id stub)
                 :name (:name item)
                 :item-kind item-kind
                 :season (::media/season-number catalog-item)
                 :episode (::media/episode-number catalog-item)}))
    (when (and (= :filler item-kind) (< idx 3))
      (log/info "Ingesting filler content"
                {:item-id (:id stub)
                 :name (:name item)
                 :item-kind item-kind}))
    {:stub stub :catalog-item catalog-item :skip? skip?}))

(defn- insert-batch!
  "Insert a batch of catalog items. Returns nil on success, an error map on
   failure."
  [catalog items]
  (try
    (catalog/add-media-batch! catalog items)
    nil
    (catch Exception e
      (log/error e "Failed to sync batch" {:batch-size (count items)})
      {:error (.getMessage e)})))

(defn- sync-page!
  "Sync one page of item stubs into the catalog, inserting in batches of
   insert-batch-size. Returns {:synced n :skipped n :errors [...]}."
  [catalog pv-config catalog-lib-id stubs report-progress page]
  (let [results (map-indexed
                 (fn [idx stub]
                   (report-progress {:phase "syncing" :page page
                                     :completed (+ (* page fetch-batch-size) idx)
                                     :current-item {:id   (some-> (:id stub) str)
                                                    :name (:name stub)}})
                   (fetch-catalog-item pv-config catalog-lib-id stub idx))
                 stubs)
        {skipped true insertable false} (group-by (comp boolean :skip?) results)
        skip-errors (mapv (fn [{:keys [stub]}]
                            {:item-id (:id stub) :reason :malformed-episode})
                          skipped)]
    (reduce (fn [totals batch]
              (if-let [err (insert-batch! catalog (vec batch))]
                (update totals :errors conj err)
                (update totals :synced + (count batch))))
            {:synced 0 :skipped (count skipped) :errors skip-errors}
            (partition-all insert-batch-size (map :catalog-item insertable)))))

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
      (let [catalog-lib-id (resolve-catalog-library-id catalog library normalized-lib)]
        (log/info "Starting PV→TS sync with pagination"
                  {:library library :catalog-lib-id catalog-lib-id :batch-size fetch-batch-size})
        (loop [page 0
               totals {:synced 0 :skipped 0 :errors []}]
          (let [offset (* page fetch-batch-size)
                item-stubs (pv/list-library-items pv-config catalog-lib-id
                                                  {:attrs item-stub-attrs
                                                   :limit fetch-batch-size
                                                   :offset offset})]
            (if (empty? item-stubs)
              ;; Pagination complete: no more items returned
              (do
                (log/info "Pseudovision media sync complete"
                          {:library library
                           :total-synced (:synced totals)
                           :total-skipped (:skipped totals)
                           :total-errors-count (count (:errors totals))})
                (when (seq (:errors totals))
                  (log/debug "Sample sync errors" {:first-errors (take 3 (:errors totals))}))
                totals)
              (do
                (log/debug "Fetching page"
                           {:page page :offset offset :batch-size (count item-stubs)})
                (report-progress {:phase "fetching" :page page :offset offset
                                  :completed offset :items-in-batch (count item-stubs)})
                (let [page-totals (sync-page! catalog pv-config catalog-lib-id
                                              item-stubs report-progress page)]
                  (recur (inc page)
                         (-> totals
                             (update :synced + (:synced page-totals))
                             (update :skipped + (:skipped page-totals))
                             (update :errors into (:errors page-totals))))))))))
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
