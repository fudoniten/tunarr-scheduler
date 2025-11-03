(ns tunarr.scheduler.curation.tags
  (:require [tunarr.scheduler.media.catalog :as catalog]
            [taoensso.timbre :as log]
            [clojure.string :as str]))

(defmulti normalize-tag! (fn [_ tag-config & _] (:type tag-config)))

(defn clean-tag
  [tag]
  (let [cleaned (-> (str tag)
                    (str/lower-case)
                    (str/replace #"&" " and ")
                    (str/replace #"\+" " plus ")
                    (str/replace #"@" " at ")
                    (str/replace #"[^\p{L}\p{N}]+" "_")
                    (str/replace #"_+" "_")
                    (str/replace #"^ +|_+$" ""))]
    (if-not (str/blank? cleaned)
      cleaned
      tag)))

(defn normalize!
  [catalog tag-configs]
  (log/info "beginning tag normalization")
  (doseq [tag (map name (catalog/get-tags catalog))]
    (let [cleaned (clean-tag tag)]
      (when-not (= tag cleaned)
        (log/info (format "renaming %s -> %s" tag cleaned))
        (catalog/rename-tag catalog tag cleaned))))
  (log/info "applying normalization rules")
  (doseq [tag-config tag-configs]
    (log/info (format "applying tag rule: %s" tag-config))
    (normalize-tag! catalog tag-config)))

(defmethod normalize-tag! :rename
  [catalog {:keys [tag new-tag]}]
  (if-not (and tag new-tag)
    (log/error (format "failed to rename tag, need :tag & :new-tag, got %s & %s"
                       tag new-tag))
    (catalog/rename-tag catalog tag new-tag)))

(defmethod normalize-tag! :delete
  [catalog {:keys [tag]}]
  (if-not tag
    (log/error (format "failed to delete tag, no :tag provided"))
    (catalog/delete-tag catalog tag)))
