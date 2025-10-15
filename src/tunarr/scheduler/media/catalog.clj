(ns tunarr.scheduler.media.catalog
  "Media catalog integration with Jellyfin or Tunarr."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [taoensso.timbre :as log]))

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

(defmulti initialize-catalog :store-type)

(defn create-persistence [config]
  (log/info "Initialising persistence layer" {:type (:type config)})
  (case (:type config)
    :filesystem {:type :filesystem :path (:path config)}
    :memory {:type :memory :state (atom {})}
    {:type :memory :state (atom {})}))

(defn close-persistence! [_]
  (log/info "Closing persistence layer"))
