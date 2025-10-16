(ns tunarr.scheduler.media.collection
  (:require [clojure.spec.alpha :as s]
            [tunarr.scheduler.media :as media]))

(s/def ::library-name keyword?)

(defprotocol MediaCollection
  (get-library-items [self library])
  (close! [self]))

(defmulti initialize-collection! :type)

(def media-collection? (partial satisfies? MediaCollection))

(s/fdef get-library-items
  :args (s/cat :self media-collection? :library ::library-name)
  :ret  (s/coll-of ::media/metadata))

