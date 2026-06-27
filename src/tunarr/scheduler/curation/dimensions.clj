(ns tunarr.scheduler.curation.dimensions
  "Validation and cleanup of dimension (category) values against the
   configured controlled vocabulary.

   Dimensions use a controlled vocabulary defined in config: the `:categories`
   map, plus the `channel` dimension whose values are the configured channel
   keys (`:channels`). The categorization LLM occasionally invents values
   outside that vocabulary — typos like `spectum` for `spectrum`, or entirely
   fabricated values like `thriller`. The helpers here let us:

   1. reject invalid values from an incoming categorization response before
      they are stored (see `filter-dimensions`), and
   2. sweep the catalog to remove values that were already persisted (see
      `clean-catalog!`).

   The `:categories` map is the same vocabulary sent to Tunabrain on
   `/categorize`, so any value outside it is a hallucination. This namespace
   is the scheduler-side guard; the upstream service should also constrain its
   own output."
  (:require [tunarr.scheduler.media :as media]
            [tunarr.scheduler.media.catalog :as catalog]
            [taoensso.timbre :as log]))

(defn- value->keyword
  "Normalize a configured category value to its value keyword. Values may be
   plain keywords/strings, or `{:value ... :description ...}` maps (the same
   shapes accepted by `tunabrain/transform-category-value`)."
  [v]
  (cond
    (map? v)     (keyword (name (:value v)))
    (keyword? v) v
    :else        (keyword (str v))))

(defn- categories->allowed
  "Build `{dimension-kw #{allowed-value-kw ...}}` from a `:categories` config
   map."
  [categories]
  (into {}
        (map (fn [[cat-name {:keys [values]}]]
               [(keyword cat-name) (into #{} (map value->keyword) values)]))
        categories))

(defn config->allowed-values
  "Derive the controlled vocabulary `{dimension-kw #{value-kw ...}}` from the
   curation config. Combines explicit `:categories` definitions with the
   `channel` dimension's vocabulary, which is the set of configured channel
   keys (`:channels`). When `:categories` already defines `channel`, the two
   are unioned."
  [{:keys [categories channels]}]
  (let [base         (categories->allowed categories)
        channel-vals (into #{} (map (comp keyword name)) (keys channels))]
    (cond-> base
      (seq channel-vals)
      (update :channel (fnil into #{}) channel-vals))))

(defn value-allowed?
  "True when `value` is in the allowed set for `dimension`. Dimensions with no
   configured vocabulary are treated as allowed — we cannot judge values for a
   dimension we have no vocabulary for."
  [allowed-values dimension value]
  (if-let [allowed (get allowed-values dimension)]
    (contains? allowed value)
    true))

(defn filter-dimensions
  "Filter a parsed categorization map against the allowed vocabulary.

   `dimensions` is `{dimension-kw [selection ...]}`, where each selection is a
   map containing `::media/category-value` (the shape produced by
   `tunabrain/request-categorization!`). Returns

     {:dimensions <valid-only> :rejected [{:dimension d :value v} ...]}

   Values for dimensions that have a configured vocabulary but fall outside it
   are dropped and reported in `:rejected`; dimensions with no configured
   vocabulary pass through untouched."
  [allowed-values dimensions]
  (reduce
   (fn [acc [dimension selections]]
     (if (contains? allowed-values dimension)
       (let [{valid true invalid false}
             (group-by (fn [sel]
                         (contains? (get allowed-values dimension)
                                    (::media/category-value sel)))
                       selections)]
         (-> acc
             (assoc-in [:dimensions dimension] (vec valid))
             (update :rejected into
                     (map (fn [sel]
                            {:dimension dimension
                             :value     (::media/category-value sel)}))
                     invalid)))
       (assoc-in acc [:dimensions dimension] (vec selections))))
   {:dimensions {} :rejected []}
   dimensions))

(defn clean-catalog!
  "Sweep the catalog for dimension values outside the configured vocabulary
   and remove them across all media. With `:dry-run true`, reports what would
   be removed without deleting anything.

   Only dimensions with a configured vocabulary are checked; dimensions with
   no vocabulary are left untouched and reported under `:skipped-dimensions`.

   Returns a report map:

     {:dimensions-checked n
      :invalid-found      n
      :values-removed     n          ; 0 on a dry run
      :removed            [{:dimension \"channel\" :value \"spectum\" :usage-count 1} ...]
      :skipped-dimensions [\"some-unconfigured-dimension\" ...]
      :dry-run            bool}"
  [catalog allowed-values & {:keys [dry-run]}]
  (let [dimensions (catalog/get-all-dimensions catalog)
        checked    (filter #(contains? allowed-values (:name %)) dimensions)
        skipped    (remove #(contains? allowed-values (:name %)) dimensions)
        removals   (for [{dim :name} checked
                         {:keys [value usage-count]} (catalog/get-dimension-values catalog dim)
                         :when (not (contains? (get allowed-values dim) value))]
                     {:dimension dim :value value :usage-count usage-count})]
    (log/info (format "dimension cleanup: %d dimensions checked, %d skipped, %d invalid values found%s"
                      (count checked) (count skipped) (count removals)
                      (if dry-run " (dry run)" "")))
    (doseq [{:keys [name]} skipped]
      (log/warn (format "dimension cleanup: skipping unconfigured dimension '%s' (no vocabulary to validate against)"
                        (clojure.core/name name))))
    (when-not dry-run
      (doseq [{:keys [dimension value usage-count]} removals]
        (log/info (format "removing invalid dimension value %s:%s (%d uses)"
                          (clojure.core/name dimension) (clojure.core/name value) usage-count))
        (catalog/purge-category-value! catalog dimension value)))
    {:dimensions-checked (count checked)
     :invalid-found      (count removals)
     :values-removed     (if dry-run 0 (count removals))
     :removed            (mapv (fn [r]
                                 (-> r
                                     (update :dimension clojure.core/name)
                                     (update :value clojure.core/name)))
                               removals)
     :skipped-dimensions (mapv (comp clojure.core/name :name) skipped)
     :dry-run            (boolean dry-run)}))
