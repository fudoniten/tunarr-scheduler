(ns tunarr.scheduler.scheduling.integration
  "The Pseudovision boundary for the layered-grid scheduler.

   Pseudovision speaks kebab-case JSON; Tunabrain and the internal scheduling
   contracts speak snake_case. This namespace owns that translation, plus
   fetch-catalog-profile: GET the aggregate (kebab-case on the wire) and
   convert it to a snake_case CatalogProfile (contracts/CatalogProfile) for
   Tunabrain and the feasibility checker."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [taoensso.timbre :as log]
            [tunarr.scheduler.backends.pseudovision.client :as pv]
            [tunarr.scheduler.scheduling.contracts :as contracts]))

;; ---------------------------------------------------------------------------
;; Key-case translation (deep, over maps nested in vectors/maps)
;; ---------------------------------------------------------------------------

(defn- rekey [key-fn m]
  (walk/postwalk
   (fn [x] (if (map? x)
             (into {} (map (fn [[k v]] [(key-fn k) v])) x)
             x))
   m))

(defn- ->snake-key [k] (keyword (str/replace (name k) "-" "_")))
(defn- ->kebab-key [k] (keyword (str/replace (name k) "_" "-")))

(defn ->snake
  "Deep-convert all map keys from kebab-case to snake_case."
  [m]
  (rekey ->snake-key m))

(defn ->kebab
  "Deep-convert all map keys from snake_case to kebab-case."
  [m]
  (rekey ->kebab-key m))

;; ---------------------------------------------------------------------------
;; CatalogProfile (Pseudovision → snake_case → Tunabrain / feasibility)
;; ---------------------------------------------------------------------------

(defn- warn-unfiltered-profile!
  "Sanity-check a tag-scoped aggregate against the tag that was requested. Media
   is mapped to a channel in Pseudovision by a `channel:<slug>` tag, so the
   scheduler scopes the aggregate with `:tag \"channel:<slug>\"`. When that
   filter is honoured every show in the profile carries the tag; if Pseudovision
   silently returns the full library instead, the profile's shows won't. That
   mismatch is otherwise invisible downstream — Tunabrain just sees a larger
   candidate set and picks off-channel content — so surface it here. Logging
   only; the profile is still returned (Pseudovision is the source of truth), and
   the check is skipped when the aggregate carries no per-show `:shows` to judge."
  [{:keys [tag]} {:keys [shows total_items]}]
  (when (and tag (seq shows))
    (let [tagged   (filter #(some #{tag} (:tags %)) shows)
          off-pool (- (count shows) (count tagged))]
      (when (pos? off-pool)
        (log/warn "catalog profile includes shows missing the requested channel tag — pseudovision may have ignored the filter"
                  {:requested-tag tag
                   :total-items total_items
                   :shows-returned (count shows)
                   :shows-with-tag (count tagged)
                   :shows-off-pool off-pool})))))

(defn fetch-catalog-profile
  "Fetch Pseudovision's catalog aggregate and assemble a snake_case
   CatalogProfile. `opts` are the client's :channel / :tag selectors. Logs (but
   does not reject) a profile that fails contract validation, or one that ignored
   the requested channel/tag filter — Pseudovision is the source of truth."
  [pv-config & [opts]]
  (let [profile (->snake (pv/get-catalog-aggregate pv-config opts))]
    (when-let [errs (contracts/humanize contracts/CatalogProfile profile)]
      (log/warn "catalog profile from pseudovision failed contract validation"
                {:errors errs}))
    (warn-unfiltered-profile! opts profile)
    profile))

;; ---------------------------------------------------------------------------
;; Display labels — resolve show ids to titles for operator-facing views
;; ---------------------------------------------------------------------------

(defn- profile-titles
  "media_id → title lookup drawn from a CatalogProfile's :shows (e.g.
   \"series:42\" → \"Cheers\"). Empty when the profile carries no shows."
  [profile]
  (into {} (keep (fn [{:keys [media_id title]}]
                   (when (and media_id (not (str/blank? title)))
                     [media_id title])))
        (:shows profile)))

(defn label-grid-content
  "Return `grid` with a human `:label` filled in on every strip's Content — and
   on `:default_content` — whose `:media_id` names a show in `profile` and that
   doesn't already carry a label. Additive and display-only: already-labelled
   content, `random:<category>`, and ids absent from the profile are left
   untouched, and `:label` never reaches Pseudovision (it isn't part of
   DailySlot). This is what lets the operator's quarterly outline show
   \"Cheers\" rather than the opaque \"series:42\"."
  [grid profile]
  (let [titles  (profile-titles profile)
        relabel (fn [{:keys [media_id label] :as content}]
                  (if (and (map? content)
                           (str/blank? label)
                           (contains? titles media_id))
                    (assoc content :label (get titles media_id))
                    content))]
    (cond-> grid
      (seq (:strips grid))
      (update :strips (fn [strips] (mapv #(update % :content relabel) strips)))

      (:default_content grid)
      (update :default_content relabel))))
