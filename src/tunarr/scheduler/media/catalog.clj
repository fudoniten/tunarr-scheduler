(ns tunarr.scheduler.media.catalog
  "Media catalog integration with Jellyfin or Tunarr."
  (:require [taoensso.timbre :as log]))

(defprotocol Catalog
  (add-media [catalog media])
  (get-media [catalog])
  (get-media-by-id [catalog media-id])
  (get-media-by-library-id [catalog library-id])
  (add-media-tags [catalog media-id tags])
  (add-media-channels [catalog media-id channels])
  (add-media-genres [catalog media-id channels])
  (get-media-by-channel [catalog channel])
  (get-media-by-tag [catalog tag])
  (get-media-by-genre [catalog genre])
  (close! [catalog]))

(defmulti initialize-catalog :type)

(defmethod initialize-catalog :default [config]
  (throw (ex-info "Unsupported catalog type" {:type (:type config)})))

(defn- ensure-memory-state
  [config]
  (if (= :memory (:type config))
    (update config :state #(or % (atom {:media {}})))
    config))

(defn create-catalog
  "Initialize a catalog implementation based on the provided configuration."
  [config]
  (let [config (merge {:type :memory} (or config {}))
        config (cond-> config
                 (string? (:type config)) (update :type keyword))
        config (ensure-memory-state config)]
    (log/info "Initializing catalog" {:type (:type config)})
    (initialize-catalog config)))

(defn close-catalog!
  "Shut down the provided catalog implementation if present."
  [catalog]
  (when (satisfies? Catalog catalog)
    (log/info "Closing catalog")
    (close! catalog)))
