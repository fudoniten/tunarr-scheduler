(ns tunarr.scheduler.curation.tags
  (:require [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.tunabrain :as tunabrain]

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
  {:type :rename :tag tag :new-tag new-tag})

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
  ;; Collect all tag renames and execute in a single batch to avoid N+1 queries
  (let [tag-pairs (for [tag (map name (catalog/get-tags catalog))
                        :let [cleaned (clean-tag tag)]
                        :when (not= tag cleaned)]
                    [tag cleaned])]
    (when (seq tag-pairs)
      (log/info (format "normalizing %d tags in batch" (count tag-pairs)))
      (doseq [[tag cleaned] tag-pairs]
        (log/info (format "renaming %s -> %s" tag cleaned)))
      (catalog/batch-rename-tags! catalog tag-pairs)))
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

;; ---------------------------------------------------------------------------
;; LLM-driven tag governance (via tunabrain)
;; ---------------------------------------------------------------------------

(defn audit-tags!
  "Audit all catalog tags with tunabrain and delete those it recommends
   removing. With :dry-run true, returns the recommendations without
   deleting anything."
  [catalog brain {:keys [dry-run]}]
  (let [tags (vec (catalog/get-tags catalog))]
    (log/info (format "auditing %d tags%s" (count tags) (if dry-run " (dry run)" "")))
    (if (empty? tags)
      {:tags-audited 0 :tags-removed 0 :removed [] :dry-run (boolean dry-run)}
      (let [{:keys [recommended-for-removal]} (tunabrain/request-tag-audit! brain tags)]
        (when-not dry-run
          (doseq [{:keys [tag reason]} recommended-for-removal]
            (log/info (format "removing tag '%s': %s" tag reason))
            (catalog/delete-tag! catalog (keyword tag))))
        (log/info (format "tag audit complete: %d audited, %d recommended for removal%s"
                          (count tags)
                          (count recommended-for-removal)
                          (if dry-run " (dry run, none removed)" "")))
        {:tags-audited (count tags)
         :tags-removed (if dry-run 0 (count recommended-for-removal))
         :removed      (vec recommended-for-removal)
         :dry-run      (boolean dry-run)}))))

(defn- triage-decision->op
  "Translate a tunabrain triage decision into a catalog operation.
   Actions are keep | drop | merge | rename; merge and rename carry the
   canonical tag in :replacement (see tunabrain api/models.py TagDecision)."
  [{:keys [tag action replacement] :as decision}]
  (cond
    (= :keep action)
    {:op :keep :tag tag}

    (= :drop action)
    {:op :delete :tag tag}

    (and replacement (contains? #{:merge :rename} action))
    {:op :rename :tag tag :new-tag replacement}

    :else
    (do (log/warn (format "skipping unrecognized triage decision for tag '%s': %s"
                          tag decision))
        {:op :skip :tag tag})))

(defn triage-tags!
  "Run tunabrain tag-governance triage over all catalog tags (with usage
   counts and example titles) and apply the keep/remove/rename decisions.
   With :dry-run true, returns the decisions without modifying the catalog.
   :target-limit caps the number of tags the upstream should aim to keep."
  [catalog brain {:keys [target-limit dry-run]}]
  (let [samples (vec (catalog/get-tag-samples catalog))]
    (log/info (format "triaging %d tags%s" (count samples) (if dry-run " (dry run)" "")))
    (if (empty? samples)
      {:tags-triaged 0 :decisions [] :dry-run (boolean dry-run)}
      (let [{:keys [decisions]} (tunabrain/request-tag-triage! brain samples
                                                               :target-limit target-limit)
            ops (group-by :op (map triage-decision->op decisions))
            {:keys [delete rename]} ops]
        (when-not dry-run
          (doseq [{:keys [tag]} delete]
            (log/info (format "triage: deleting tag '%s'" tag))
            (catalog/delete-tag! catalog (keyword tag)))
          (when (seq rename)
            (doseq [{:keys [tag new-tag]} rename]
              (log/info (format "triage: renaming tag '%s' -> '%s'" tag new-tag)))
            (catalog/batch-rename-tags! catalog (mapv (juxt :tag :new-tag) rename))))
        (log/info (format "tag triage complete: %d triaged, %d kept, %d deleted, %d renamed, %d skipped%s"
                          (count samples)
                          (count (:keep ops))
                          (count delete)
                          (count rename)
                          (count (:skip ops))
                          (if dry-run " (dry run, no changes applied)" "")))
        {:tags-triaged (count samples)
         :kept         (count (:keep ops))
         :deleted      (count delete)
         :renamed      (count rename)
         :skipped      (count (:skip ops))
         :decisions    (vec decisions)
         :dry-run      (boolean dry-run)}))))
