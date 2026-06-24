(ns tunarr.scheduler.scheduling.integration
  "The Pseudovision boundary for the layered-grid scheduler.

   Pseudovision speaks kebab-case JSON; Tunabrain and the internal scheduling
   contracts speak snake_case. This namespace owns that translation, plus the
   two cross-system operations:

   • fetch-catalog-profile — GET the aggregate, convert to a snake_case
     CatalogProfile (contracts/CatalogProfile) for Tunabrain + the feasibility
     checker.
   • publish-daily-slots! / publish-week! — convert the expander's snake_case
     DailySlots to kebab-case and POST them to Pseudovision (the weekly
     deterministic step; no Tunabrain call)."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [taoensso.timbre :as log]
            [tunarr.scheduler.backends.pseudovision.client :as pv]
            [tunarr.scheduler.scheduling.contracts :as contracts]
            [tunarr.scheduler.scheduling.plans :as plans]))

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

(defn fetch-catalog-profile
  "Fetch Pseudovision's catalog aggregate and assemble a snake_case
   CatalogProfile. `opts` are the client's :channel / :tag selectors. Logs (but
   does not reject) a profile that fails contract validation — Pseudovision is
   the source of truth."
  [pv-config & [opts]]
  (let [profile (->snake (pv/get-catalog-aggregate pv-config opts))]
    (when-let [errs (contracts/humanize contracts/CatalogProfile profile)]
      (log/warn "catalog profile from pseudovision failed contract validation"
                {:errors errs}))
    profile))

;; ---------------------------------------------------------------------------
;; DailySlot publication (expander → kebab-case → Pseudovision)
;; ---------------------------------------------------------------------------

(defn publish-daily-slots!
  "Convert snake_case DailySlots (expander output) to Pseudovision's kebab-case
   and POST them to a channel. `channel-id` is Pseudovision's integer id or a
   channel-number string. Returns the DailySlotIngestResult."
  [pv-config channel-id slots]
  (pv/push-daily-slots! pv-config channel-id (mapv ->kebab slots)))

(defn publish-week!
  "Expand the channel's stored grid + overrides over [start, end) and push the
   resulting DailySlots to Pseudovision. The deterministic weekly step: no
   Tunabrain call. Returns {:channel :pv-channel-id :start :end :slot-count
   :result}, or {:skipped :no-grid} when no grid is frozen for the window."
  [ex pv-config channel pv-channel-id start end]
  (let [{:keys [grid_id slots]} (plans/preview ex channel start end)]
    (if (nil? grid_id)
      (do (log/info "publish-week!: no frozen grid; skipping"
                    {:channel channel :start (str start) :end (str end)})
          {:channel channel :pv-channel-id pv-channel-id
           :start (str start) :end (str end) :skipped :no-grid})
      (let [result (publish-daily-slots! pv-config pv-channel-id slots)]
        (log/info "publish-week!: pushed daily slots"
                  {:channel channel :pv-channel-id pv-channel-id
                   :slot-count (count slots) :result result})
        {:channel channel :pv-channel-id pv-channel-id
         :start (str start) :end (str end)
         :slot-count (count slots) :result result}))))
