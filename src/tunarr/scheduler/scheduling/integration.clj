(ns tunarr.scheduler.scheduling.integration
  "The Pseudovision boundary for the layered-grid scheduler.

   Pseudovision speaks kebab-case JSON; Tunabrain and the internal scheduling
   contracts speak snake_case. This namespace owns that translation, plus the
   two cross-system operations:

   • fetch-catalog-profile — GET the aggregate (kebab-case on the wire),
     convert to a snake_case CatalogProfile (contracts/CatalogProfile) for
     Tunabrain + the feasibility checker.
   • publish-daily-slots! / publish-week! — convert the expander's snake_case
     DailySlots to kebab-case and POST them to Pseudovision (the weekly
     deterministic step; no Tunabrain call)."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [taoensso.timbre :as log]
            [tunarr.scheduler.backends.pseudovision.client :as pv]
            [tunarr.scheduler.scheduling.contracts :as contracts]
            [tunarr.scheduler.scheduling.plans :as plans]
            [tunarr.scheduler.scheduling.policy :as policy]
            [tunarr.scheduler.scheduling.storage :as storage]))

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
;; DailySlot publication (expander → kebab-case → Pseudovision)
;; ---------------------------------------------------------------------------

(defn publish-daily-slots!
  "Convert snake_case DailySlots (expander output) to Pseudovision's kebab-case
   and POST them to a channel. `channel-id` is Pseudovision's integer id or a
   channel-number string. Returns the DailySlotIngestResult.

   If `channel-tag` is supplied, it is prepended to every slot's
   `:category_filters` so that `random:<genre>` resolution is scoped to the
   channel's own media pool rather than the entire catalog.

   Sent in kebab-case to match the rest of the Pseudovision wire protocol."
  [pv-config channel-id slots & {:keys [channel-tag]}]
  (let [tagged (if channel-tag
                 (mapv #(update % :category_filters
                                (fn [f] (vec (cons channel-tag (remove #{channel-tag} f))))) slots)
                 slots)]
    (pv/push-daily-slots! pv-config channel-id (mapv ->kebab tagged))))

(defn publish-week!
  "Expand the channel's stored grid + overrides over [start, end) and push the
   resulting DailySlots to Pseudovision. The deterministic weekly step: no
   Tunabrain call. Returns {:channel :pv-channel-id :start :end :slot-count
   :result}, or {:skipped :no-grid} when no grid is frozen for the window.

   `channel-tag` (optional, e.g. \"channel:hua\") is injected into every slot's
   `:category_filters` so random resolution stays scoped to the channel pool."
  [ex pv-config channel pv-channel-id start end & {:keys [channel-tag]}]
  (let [{:keys [grid_id slots default_content]} (plans/preview ex channel start end)]
    (if (nil? grid_id)
      (do (log/info "publish-week!: no frozen grid; skipping"
                    {:channel channel :start (str start) :end (str end)})
          {:channel channel :pv-channel-id pv-channel-id
           :start (str start) :end (str end) :skipped :no-grid})
      ;; Air-time backstop: substitute the grid's default content for any slot a
      ;; content-policy watershed forbids at this time of day. Deterministic and
      ;; a no-op when the channel has no policy.
      (let [pol      (storage/get-policy ex channel)
            enforced (policy/enforce-slots slots pol default_content)
            vetoed   (- (count enforced) (count slots))
            result   (publish-daily-slots! pv-config pv-channel-id enforced :channel-tag channel-tag)]
        (when (pos? vetoed)
          (log/info "publish-week!: watershed substitutions applied"
                    {:channel channel :extra-segments vetoed}))
        (log/info "publish-week!: pushed daily slots"
                  {:channel channel :pv-channel-id pv-channel-id
                   :slot-count (count enforced) :result result})
        {:channel channel :pv-channel-id pv-channel-id
         :start (str start) :end (str end)
         :slot-count (count enforced) :result result}))))
