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
  (close-catalog! [catalog]))

(defmulti initialize-catalog! :type)

