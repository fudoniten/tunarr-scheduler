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
         vec))
  (add-media-tags! [_ media-id tags]
    (update-media! state media-id #(update % ::media/tags conj-distinct tags))
    nil)
  (add-media-channels! [_ media-id channels]
    (update-media! state media-id #(update % ::media/channels conj-distinct channels))
    nil)
  (add-media-genres! [_ media-id genres]
    (update-media! state media-id #(update % ::media/genres conj-distinct genres))
    nil)
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
  (get-media-by-genre [_ genre]
    (->> (get @state :media {})
         vals
         (filter #(some #{genre} (::media/genres %)))
         vec))
  (close-catalog! [_]
    (reset! state {:media {}})
    nil))

(defmethod catalog/initialize-catalog! :memory
  [{:keys [state]}]
  (->MemoryCatalog (or state (atom {:media {}}))))
