(ns tunarr.scheduler.curation.tags
  (:require [tunarr.scheduler.media.catalog :as catalog]
            
            [clojure.string :as str]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            
            [taoensso.timbre :as log]))

(defmulti load-tag-rule second)

(defmethod load-tag-rule "merge"
  [[tag _ new-tag & _]]
  {:type :rename :tag tag :new-tag new-tag})

(defmethod load-tag-rule "rename"
  [[tag _ new-tag & _]]
  {:type :drop :tag tag :new-tag new-tag})

(defmethod load-tag-rule "drop"
  [[tag _ & _]]
  {:type :drop :tag tag})

(defmethod load-tag-rule ""
  [[tag & _]]
  {:type :no-op :tag tag})

(defmethod load-tag-rule :default
  [& args]
  (throw (ex-info "unrecognized or missing rule type"
                  {:args args})))

(defn load-tag-transforms
  [file]
  (when-not (.exists (io/file file))
    (throw (ex-info (format "file does not exist: %s" file)
                    {:filename file})))
  (let [rows (with-open [reader (io/reader file)]
               (doall (csv/read-csv reader)))]
    (for [row rows]
      (load-tag-rule row))))

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
  [catalog {:keys [tag-transforms-file]}]
  (log/info "beginning tag normalization")
  (doseq [tag (map name (catalog/get-tags catalog))]
    (let [cleaned (clean-tag tag)]
      (when-not (= tag cleaned)
        (log/info (format "renaming %s -> %s" tag cleaned))
        (catalog/rename-tag! catalog tag cleaned))))
  (if (not tag-transforms-file)
    (log/warn "skipping tag transforms as no rules were provided")
    (do (log/info "applying normalization rules")
        (let [tag-transforms (load-tag-transforms tag-transforms-file)]
          (doseq [tag-rule tag-transforms]
            (log/info (format "applying tag rule: %s" tag-rule))
            (normalize-tag! catalog tag-rule))))))

(defmethod normalize-tag! :rename
  [catalog {:keys [tag new-tag] :as opts}]
  (if-not (and tag new-tag)
    (log/error (format "failed to rename tag, need :tag & :new-tag: %s" opts))
    (do (log/info (format "renaming tag: %s -> %s" tag new-tag))
      (catalog/rename-tag! catalog tag new-tag))))

(defmethod normalize-tag! :drop
  [catalog {:keys [tag] :as opts}]
  (if-not tag
    (log/error (format "failed to delete tag, no :tag provided: %s" opts))
    (do (log/info (format "dropping tag: %s" tag))
        (catalog/delete-tag! catalog tag))))

(defmethod normalize-tag! :no-op
  [_ {:keys [tag]}]
  (log/debug (format "no operation specified for tag %s" tag)))
