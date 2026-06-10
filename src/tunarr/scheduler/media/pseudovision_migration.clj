(ns tunarr.scheduler.media.pseudovision-migration
  "One-time migration to push existing LLM metadata from tunarr-scheduler
   catalog to Pseudovision.
   
   This allows us to preserve existing categorization work while switching
   to Pseudovision as the source of truth going forward."
  (:require [tunarr.scheduler.backends.pseudovision.client :as pv]
            [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.media.sql-catalog :as sql-catalog]
            [honey.sql.helpers :refer [select from where]]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Query Local Catalog
;; ---------------------------------------------------------------------------

(defn get-all-media-with-metadata
  "Query tunarr-scheduler catalog for all media with their metadata.
   
   Returns vector of maps with:
   - :jellyfin-id
   - :name
   - :tags (vector of tag strings)
   - :categories (map of category -> values)"
  [catalog-db]
  (let [executor (:executor catalog-db)
        ;; Get all media IDs
        media-ids (sql-catalog/sql:fetch! executor
                    (-> (select :id :name)
                        (from :media)))
        
        ;; For each media, get tags and categories
        ;; Note: HoneySQL returns qualified keywords like :media/id and :media/name
        results (map (fn [row]
                       (let [id (or (:media/id row) (:id row))
                             name (or (:media/name row) (:name row))
                             tags (catalog/get-media-tags catalog-db id)
                             categories (catalog/get-media-categories catalog-db id)]
                         {:jellyfin-id id
                          :name name
                          :tags (vec tags)
                          :categories (reduce (fn [acc {:keys [category category-value]}]
                                               (update acc (keyword category) 
                                                       (fnil conj []) 
                                                       (keyword category-value)))
                                             {}
                                             categories)}))
                     media-ids)]
    
    (log/info "Loaded catalog metadata" 
              {:total-items (count results)
               :items-with-tags (count (filter #(seq (:tags %)) results))
               :items-with-categories (count (filter #(seq (:categories %)) results))})
    results))

;; ---------------------------------------------------------------------------
;; Map to Pseudovision
;; ---------------------------------------------------------------------------

(defn find-pseudovision-item
  "Find Pseudovision media item by Jellyfin ID.
   
   Returns Pseudovision media item with :id or nil if not found."
  [pv-config jellyfin-id]
  (try
    (pv/find-media-item-by-jellyfin-id pv-config jellyfin-id)
    (catch Exception e
      (log/warn "Failed to find Pseudovision item" 
                {:jellyfin-id jellyfin-id :error (ex-message e)})
      nil)))

(defn flatten-categories-to-tags
  "Convert category map to flat tag list.
   
   Example:
   {:channel [:comedy :action]
    :time-slot [:primetime]}
   
   =>
   ['channel:comedy' 'channel:action' 'time-slot:primetime']"
  [categories]
  (mapcat (fn [[category values]]
            (->> values
                 (filter some?)  ;; Filter out nil values
                 (map #(str (clojure.core/name category) ":" (clojure.core/name %)))))
          categories))

;; ---------------------------------------------------------------------------
;; Migration
;; ---------------------------------------------------------------------------

(defn- item-migration-tags
  "Build the list of tag strings to push for a catalog item: its tags
   (keywords) plus, when requested, its categories flattened to
   category:value strings. Drops nil/blank entries."
  [{:keys [tags categories]} include-categories?]
  (->> (concat (map clojure.core/name tags)
               (when include-categories?
                 (flatten-categories-to-tags categories)))
       (remove #(or (nil? %) (empty? (str %))))
       vec))

(defn- push-item-tags!
  "Push tags to Pseudovision for one item. Returns a migration status map."
  [pv-config pv-id {:keys [jellyfin-id name]} all-tags]
  (try
    (pv/add-tags! pv-config pv-id all-tags)
    (log/info "Migrated tags"
              {:jellyfin-id jellyfin-id
               :pv-id pv-id
               :name name
               :tags-added (count all-tags)})
    {:status :success :tags-added (count all-tags) :pv-id pv-id}
    (catch Exception e
      (log/error e "Failed to migrate tags"
                 {:jellyfin-id jellyfin-id :pv-id pv-id})
      {:status :error :error (ex-message e)})))

(defn migrate-item!
  "Migrate a single media item's metadata to Pseudovision.

   Returns:
   - {:status :success :tags-added N} - if successful
   - {:status :not-found} - if item not in Pseudovision
   - {:status :skipped :reason X} - if skipped
   - {:status :error :error X} - if failed"
  [pv-config catalog-item opts]
  (let [{:keys [jellyfin-id name]} catalog-item
        dry-run? (:dry-run opts false)
        include-categories? (:include-categories opts true)
        _ (log/debug "Migrating item" {:jellyfin-id jellyfin-id :name name})
        pv-item (find-pseudovision-item pv-config jellyfin-id)
        all-tags (when pv-item (item-migration-tags catalog-item include-categories?))]
    (cond
      (nil? pv-item)
      (do (log/warn "Item not found in Pseudovision"
                    {:jellyfin-id jellyfin-id :name name})
          {:status :not-found})

      (empty? all-tags)
      (do (log/debug "No tags to migrate" {:jellyfin-id jellyfin-id})
          {:status :skipped :reason :no-tags})

      dry-run?
      (do (log/info "DRY RUN: Would add tags"
                    {:jellyfin-id jellyfin-id
                     :pv-id (:id pv-item)
                     :name name
                     :tags all-tags})
          {:status :dry-run :tags all-tags :pv-id (:id pv-item)})

      :else
      (push-item-tags! pv-config (:id pv-item) catalog-item all-tags))))

(defn migrate-all!
  "Migrate all metadata from tunarr-scheduler catalog to Pseudovision.
   
   Options:
   - :dry-run - If true, log what would be done but don't make changes
   - :include-categories - If true, convert categories to tags (default: true)
   - :batch-size - Process items in batches (default: 10)
   - :delay-ms - Delay between items in ms (default: 100)
   
   Returns migration summary map."
  [catalog-db pv-config opts]
  (let [dry-run? (:dry-run opts false)
        batch-size (:batch-size opts 10)
        delay-ms (:delay-ms opts 100)
        
        _ (log/info "Starting Pseudovision migration" 
                    {:dry-run dry-run? :batch-size batch-size})
        
        ;; Load all catalog items
        items (get-all-media-with-metadata catalog-db)
        
        ;; Track results
        results (atom {:total 0
                      :success 0
                      :not-found 0
                      :skipped 0
                      :errors 0
                      :dry-run 0})
        
        ;; Process in batches
        _ (doseq [batch (partition-all batch-size items)]
            (doseq [item batch]
              (let [result (migrate-item! pv-config item opts)]
                (swap! results update :total inc)
                (case (:status result)
                  :success   (swap! results update :success inc)
                  :not-found (swap! results update :not-found inc)
                  :skipped   (swap! results update :skipped inc)
                  :error     (swap! results update :errors inc)
                  :dry-run   (swap! results update :dry-run inc)))
              
              ;; Rate limiting
              (when-not dry-run?
                (Thread/sleep delay-ms)))
            
            (log/info "Batch complete" {:progress @results}))]
    
    (let [final @results]
      (log/info "Migration complete" final)
      final)))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(defn validate-migration
  "Check migration results and compare tag counts.
   
   Returns validation report."
  [catalog-db pv-config]
  (let [items (get-all-media-with-metadata catalog-db)
        total (count items)
        
        validation-results 
        (map (fn [{:keys [jellyfin-id tags categories]}]
               (if-let [pv-item (find-pseudovision-item pv-config jellyfin-id)]
                 (let [pv-tags (pv/get-tags pv-config (:id pv-item))
                       expected-count (+ (count tags) 
                                        (count (flatten-categories-to-tags categories)))
                       actual-count (count pv-tags)
                       match? (= expected-count actual-count)]
                   {:jellyfin-id jellyfin-id
                    :pv-id (:id pv-item)
                    :expected expected-count
                    :actual actual-count
                    :match? match?})
                 {:jellyfin-id jellyfin-id
                  :not-found true}))
             (take 20 items))] ;; Sample first 20
    
    (log/info "Validation complete" 
              {:sampled 20
               :matches (count (filter :match? validation-results))
               :mismatches (count (filter #(and (not (:not-found %)) 
                                               (not (:match? %))) 
                                         validation-results))
               :not-found (count (filter :not-found validation-results))})
    
    validation-results))
