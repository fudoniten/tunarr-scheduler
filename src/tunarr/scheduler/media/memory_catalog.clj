(ns tunarr.scheduler.media.memory-catalog
  (:require [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.media :as media]))

(defn- distinct-vector
  [coll]
  (->> coll
       (remove nil?)
       distinct
       vec))

(defn- normalize-media
  [media]
  (let [normalize (fn [coll]
                    (if (nil? coll)
                      []
                      (distinct-vector coll)))]
    (cond-> media
      (contains? media ::media/tags) (update ::media/tags normalize)
      (contains? media ::media/channels) (update ::media/channels normalize)
      (contains? media ::media/genres) (update ::media/genres normalize))))

(defn- conj-distinct
  [existing additions]
  (cond
    (and (nil? existing) (nil? additions)) nil
    (nil? existing) (distinct-vector additions)
    (nil? additions) existing
    :else (distinct-vector (concat existing additions))))

(defn- update-media!
  [state media-id f & args]
  (swap! state
         (fn [{:keys [media] :as db}]
           (let [media-map (or media {})]
             (if-let [current (get media-map media-id)]
               (assoc db :media (assoc media-map media-id (apply f current args)))
               (throw (ex-info "Media not found" {:media-id media-id})))))))

(defrecord MemoryCatalog [state]
  catalog/Catalog
  (add-media! [_ media]
    (let [media-id (::media/id media)]
      (when-not media-id
        (throw (ex-info "Cannot add media without an id" {:media media})))
      (let [normalized (assoc (normalize-media media) ::media/id media-id)]
        (swap! state
               (fn [{:keys [media] :as db}]
                 (let [media-map (or media {})
                       existing (get media-map media-id)]
                   (assoc db :media (assoc media-map media-id (merge existing normalized))))))))
    nil)

  (add-media-batch! [self media-items]
    (doseq [media media-items]
      (catalog/add-media! self media))
    nil)

  (get-media [_]
    (->> (get @state :media {})
         vals
         vec))
  (get-media-by-id [_ media-id]
    (if-let [item (get-in @state [:media media-id])]
      [item]
      []))
  (get-media-by-library-id [_ library-id]
    (->> (get @state :media {})
         vals
         (filter #(= library-id (::media/library-id %)))
         (remove #(= :episode (::media/type %)))
         vec))
  (add-media-tags! [_ media-id tags]
    (update-media! state media-id #(update % ::media/tags conj-distinct tags))
    nil)
  ;; DEPRECATED: Hardcoded channel assignment. Channels are dimensions now.
  ;; Use dimension-based storage or add-media-tags! with "channel:NAME" instead.
  ;; See DIMENSION_CLEANUP.md Phase 3.
  (add-media-channels! [_ media-id channels]
    (update-media! state media-id #(update % ::media/channels conj-distinct channels))
    nil)
  ;; DEPRECATED: Hardcoded genre assignment. Genres are dimensions now.
  ;; Use dimension-based storage or add-media-tags! with "genre:NAME" instead.
  ;; See DIMENSION_CLEANUP.md Phase 3.
  (add-media-genres! [_ media-id genres]
    (update-media! state media-id #(update % ::media/genres conj-distinct genres))
    nil)
  ;; DEPRECATED: Hardcoded channel filter. Channels are dimensions now.
  ;; Use get-media-by-tag with "channel:NAME" tag instead.
  ;; See DIMENSION_CLEANUP.md Phase 3.
  (get-media-by-channel [_ channel]
    (->> (get @state :media {})
         vals
         (filter #(some #{channel} (::media/channels %)))
         vec))
  (get-media-by-tag [_ tag]
    (->> (get @state :media {})
         vals
         (filter #(some #{tag} (::media/tags %)))
         vec))
  ;; DEPRECATED: Hardcoded genre filter. Genres are dimensions now.
  ;; Use get-media-by-tag with "genre:NAME" tag instead.
  ;; See DIMENSION_CLEANUP.md Phase 3.
  (get-media-by-genre [_ genre]
    (->> (get @state :media {})
         vals
         (filter #(some #{genre} (::media/genres %)))
         vec))
  (get-tags [_]
    (->> (get @state :media {})
         vals
         (mapcat ::media/tags)
         distinct
         vec))
  ;; DEPRECATED: Hardcoded channel list. Channels are dimensions now.
  ;; Use get-media-categories or tag-based queries instead.
  ;; See DIMENSION_CLEANUP.md Phase 3.
  (get-channels [_]
    (->> (get @state :media {})
         vals
         (mapcat ::media/channels)
         (remove nil?)
         distinct
         (mapv (fn [ch] {:name (name ch)}))))
  ;; DEPRECATED: Hardcoded genre list. Genres are dimensions now.
  ;; Use get-media-categories or tag-based queries instead.
  ;; See DIMENSION_CLEANUP.md Phase 3.
  (get-genres [_]
    (->> (get @state :media {})
         vals
         (mapcat ::media/genres)
         distinct
         vec))
  (rename-tag! [self old-tag new-tag]
    ;; Replace old-tag with new-tag in all media items
    (doseq [media (catalog/get-media-by-tag self old-tag)]
      (let [media-id (::media/id media)
            current-tags (::media/tags media)
            updated-tags (-> current-tags
                           (vec)
                           (conj new-tag)
                           (distinct)
                           (vec)
                           (#(remove #{old-tag} %))
                           (vec))]
        (update-media! state media-id assoc ::media/tags updated-tags)))
    nil)
  (batch-rename-tags! [self tag-pairs]
    ;; Perform all tag renames
    (doseq [[old-tag new-tag] tag-pairs]
      (catalog/rename-tag! self old-tag new-tag))
    nil)
  (get-episodes-by-series [_ series-id]
    (->> (get @state :media {})
         vals
         (filter #(= series-id (::media/parent-id %)))
         (sort-by (juxt ::media/season-number ::media/episode-number))
         vec))

  (get-episode [_ series-id season-number episode-number]
    (->> (get @state :media {})
         vals
         (filter #(and (= series-id (::media/parent-id %))
                       (= season-number (::media/season-number %))
                       (= episode-number (::media/episode-number %))))
         first))

  (get-effective-tags [_ media-id]
    (let [item (get-in @state [:media media-id])
          own-tags (or (::media/tags item) [])
          parent-tags (when-let [pid (::media/parent-id item)]
                        (or (::media/tags (get-in @state [:media pid])) []))]
      (vec (distinct (concat own-tags parent-tags)))))

  (get-media-categories [_ media-id]
    (or (get-in @state [:categories media-id]) {}))

  (get-media-category-values [self media-id category]
    (get (catalog/get-media-categories self media-id) category []))

  (add-media-category-value! [_ media-id category value rationale]
    (swap! state update-in [:categories media-id category]
           #(distinct (conj (or % []) value)))
    nil)

  (add-media-category-values! [_ media-id category values]
    (swap! state update-in [:categories media-id category]
           #(distinct (concat (or % []) (map ::media/category-value values))))
    nil)

  (set-media-category-values! [_ media-id category values]
    (swap! state assoc-in [:categories media-id category]
           (distinct (map ::media/category-value values)))
    nil)

  (delete-media-category-value! [_ media-id category value]
    (swap! state update-in [:categories media-id category]
           #(remove #{value} (or % [])))
    nil)

  (delete-media-category-values! [_ media-id category]
    (swap! state update-in [:categories media-id]
           #(dissoc % category))
    nil)

  (get-effective-categories [_ media-id]
    (let [item (get-in @state [:media media-id])
          own-cats (or (get-in @state [:categories media-id]) {})
          parent-cats (when-let [pid (::media/parent-id item)]
                        (or (get-in @state [:categories pid]) {}))]
      (if (seq parent-cats)
        (merge parent-cats own-cats)
        own-cats)))

  (get-all-dimensions [_]
    (->> (get @state :categories {})
         vals
         (mapcat keys)
         distinct
         (map (fn [dim]
                {:name dim
                 :value-count (count (distinct
                                       (mapcat (fn [cats]
                                                 (get cats dim []))
                                               (vals (get @state :categories {})))))}))))

  (get-dimension-values [_ dimension]
    (->> (get @state :categories {})
         vals
         (mapcat #(get % dimension []))
         distinct
         (map (fn [val]
                {:value val
                 :usage-count (count (filter #(some #{val} (get-in % [dimension]))
                                              (vals (get @state :categories {}))))}))))

  (close-catalog! [_]
    (reset! state {:media {}})
    nil))

(defmethod catalog/initialize-catalog! :memory
  [{:keys [state]}]
  (->MemoryCatalog (or state (atom {:media {}}))))
