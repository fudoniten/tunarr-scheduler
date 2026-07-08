(ns tunarr.scheduler.scheduling.orchestration
  "The propose → check → repair → freeze loop, per channel.

   This ties the pieces together (handoff §4.6). Quarterly: assemble a
   CatalogProfile from Pseudovision, ask Tunabrain to propose a Grid, run the
   deterministic feasibility checker, and — while there are blocking shortfalls
   and repair budget remains — ask Tunabrain to repair, re-checking each round.
   Then freeze + store the grid with its feasibility snapshot. Monthly: ask
   Tunabrain for sparse overrides against the frozen grid and store them.

   Operator guidance (the per-channel manual-input surface) is pulled in and fed
   to the propose calls; it steers but never gates. The weekly deterministic
   publish step lives in `scheduling.integration/publish-week!` (no Tunabrain
   call).

   `components` is a map of {:executor :tunabrain :pv-config}. The three external
   calls — the CatalogProfile fetch and the two Tunabrain proposals — are taken
   from `components` (`:fetch-profile`, `:propose-grid`, `:repair-grid`,
   `:propose-overrides`) and default to the real implementations, so callers can
   inject stubs without global redefs. `channel-spec` is the {:name :description}
   stub Tunabrain expects; its :name is also the storage key."
  (:require [taoensso.timbre :as log]
            [tunarr.scheduler.tunabrain :as tb]
            [tunarr.scheduler.scheduling.integration :as integ]
            [tunarr.scheduler.scheduling.feasibility :as feasibility]
            [tunarr.scheduler.scheduling.storage :as storage]
            [tunarr.scheduler.scheduling.plans :as plans]))

(defn- guidance [executor channel]
  (or (storage/get-guidance executor channel) {}))

(defn run-quarterly!
  "Propose → check → repair → freeze a quarterly Grid for one channel. Bounds the
   repair loop at `:max-repairs` (default 3), then freezes best-effort with the
   final feasibility status attached. Returns the stored grid record plus
   `:feasibility-status` and `:repairs`."
  [{:keys [executor tunabrain pv-config fetch-profile propose-grid repair-grid]
    :or   {fetch-profile integ/fetch-catalog-profile
           propose-grid  tb/propose-quarterly-grid!
           repair-grid   tb/repair-quarterly-grid!}}
   channel-spec quarter year
   & {:keys [cost-tier default-media-id max-repairs catalog-tag]
      :or   {cost-tier "balanced" max-repairs 3}}]
  (let [channel        (:name channel-spec)
        profile        (fetch-profile pv-config {:channel channel :tag catalog-tag})
        g              (guidance executor channel)
        [hstart hend]  (plans/quarter-range quarter year)
        proposed       (propose-grid
                        tunabrain
                        {:channel channel-spec :quarter quarter :year year
                         :catalog-profile profile
                         :quarterly-theme (:quarterly_theme g)
                         :strategic-guidance (:strategic_guidance g)
                         :default-media-id default-media-id
                         :cost-tier cost-tier})
        grid-id        (:grid_id proposed)]
    (loop [grid (:grid proposed), repairs 0]
      (let [report (feasibility/check grid profile (str hstart) (str hend))]
        (if (or (= "ok" (:overall_status report)) (>= repairs max-repairs))
          ;; Fill in show titles (from the CatalogProfile we already have) so the
          ;; frozen grid is self-describing in operator views; display-only, so it
          ;; runs after feasibility and never affects the check or playout.
          (let [labeled (integ/label-grid-content grid profile)
                stored  (storage/freeze-grid! executor channel quarter year labeled
                                              :grid-id grid-id :feasibility report)]
            (log/info "quarterly grid frozen"
                      {:channel channel :quarter quarter :year year
                       :status (:overall_status report) :repairs repairs})
            (assoc stored :feasibility-status (:overall_status report) :repairs repairs))
          (let [repaired (repair-grid
                          tunabrain
                          {:channel channel-spec :catalog-profile profile
                           :current-grid grid :feasibility-report report
                           :cost-tier cost-tier})]
            (log/info "repairing quarterly grid"
                      {:channel channel :round (inc repairs) :status (:overall_status report)})
            (recur (:grid repaired) (inc repairs))))))))

(defn run-monthly!
  "Ask Tunabrain for sparse monthly Overrides against the channel's frozen grid
   and store them. An empty override list is normal. Throws if no grid is frozen
   for the month's quarter."
  [{:keys [executor tunabrain pv-config fetch-profile propose-overrides]
    :or   {fetch-profile     integ/fetch-catalog-profile
           propose-overrides tb/propose-monthly-overrides!}}
   channel-spec month
   & {:keys [cost-tier catalog-tag] :or {cost-tier "balanced"}}]
  (let [channel     (:name channel-spec)
        as-of       (str month "-01")
        quarter     (plans/quarter-of as-of)
        year        (plans/year-of as-of)
        grid-record (storage/current-grid executor channel quarter year)]
    (when (nil? grid-record)
      (throw (ex-info "no frozen grid to base monthly overrides on"
                      {:channel channel :month month :quarter quarter :year year})))
    (let [profile (fetch-profile pv-config {:channel channel :tag catalog-tag})
          g       (guidance executor channel)
          resp    (propose-overrides
                   tunabrain
                   {:channel channel-spec :month month :grid (:grid grid-record)
                    :catalog-profile profile
                    :monthly-theme (:monthly_theme g)
                    :planned-events (:planned_events g)
                    :strategic-guidance (:strategic_guidance g)
                    :cost-tier cost-tier})
          stored  (storage/store-overrides! executor channel month (:overrides resp)
                                            :overrides-id (:overrides_id resp))]
      (log/info "monthly overrides stored"
                {:channel channel :month month :count (count (:overrides resp))})
      stored)))
