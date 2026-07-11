(ns tunarr.scheduler.scheduling.review
  "The schedule review / critique loop (Phase 7), Tunarr Scheduler's half.

   The feasibility loop guarantees a grid is structurally sound; it says nothing
   about whether the realized week is any *good* (variety, daypart-fit,
   series-usage, pacing). Tunabrain's review/revise endpoints judge that — but
   only from a CONCRETE week: real show titles in real slots. This namespace
   builds that concrete week deterministically (expand the frozen grid for one
   representative week, label each slot with its show title) and drives the
   bounded loop: review → revise on fail → re-review, re-checking feasibility
   after each taste revision so a revision can never smuggle in a capacity
   shortfall.

   Pure/deterministic except for the injected Tunabrain calls, so it's tested
   the same way `orchestration` is — stub `review-fn`/`revise-fn`, real
   sample-week + feasibility over in-memory data."
  (:require [clojure.string :as str]
            [taoensso.timbre :as log]
            [tunarr.scheduler.scheduling.calendar :as cal]
            [tunarr.scheduler.scheduling.expander :as expander]
            [tunarr.scheduler.scheduling.feasibility :as feasibility])
  (:import [java.time LocalDateTime]))

;; A fixed reference week starting Monday 2024-01-01 (a Monday). Expansion is
;; weekday-based and deterministic, so any Monday yields the same weekly shape;
;; pinning one keeps the sample week stable and testable.
(def ^:private sample-week-start "2024-01-01")
(def ^:private sample-week-end "2024-01-08")

(defn- media-kind [media-id] (first (str/split (str media-id) #":" 2)))
(defn- media-arg [media-id] (second (str/split (str media-id) #":" 2)))

(defn title-index
  "media_id → title map drawn from a CatalogProfile's :shows (e.g.
   \"series:42\" → \"Cheers\"). Same lookup `integration/label-grid-content`
   uses, rebuilt here to keep the sample-week builder self-contained."
  [profile]
  (into {} (keep (fn [{:keys [media_id title]}]
                   (when (and media_id (not (str/blank? title)))
                     [media_id title])))
        (:shows profile)))

(defn slot-label
  "A human-readable label for a slot's `media-id`, for the reviewer to read:
   a named show resolves to its title; a `random:<category>` pool renders as
   \"random: <category> pool\"; anything unresolved falls back to the raw id."
  [media-id titles]
  (case (media-kind media-id)
    ("series" "movie") (get titles media-id media-id)
    "random"           (str "random: " (media-arg media-id) " pool")
    media-id))

(defn- hhmm [^String iso-datetime]
  (let [t (LocalDateTime/parse iso-datetime)]
    (format "%02d:%02d" (.getHour t) (.getMinute t))))

(defn- weekday-code [^String iso-datetime]
  (cal/->code (.toLocalDate (LocalDateTime/parse iso-datetime))))

(defn sample-week
  "Expand `grid` (no overrides) over one representative week and label each
   DailySlot with its show title, producing a vector of ReviewSlot maps
   ({:day :start :end :label :media_id :strategy}). This is the concrete week
   the reviewer critiques — deterministic, no Pseudovision round-trip."
  [grid profile]
  (let [titles (title-index profile)
        slots  (expander/expand grid [] sample-week-start sample-week-end)]
    (mapv (fn [{:keys [start_time end_time media_id media_selection_strategy]}]
            (cond-> {:day     (weekday-code start_time)
                     :start   (hhmm start_time)
                     :end     (hhmm end_time)
                     :label   (slot-label media_id titles)
                     :media_id media_id}
              media_selection_strategy (assoc :strategy media_selection_strategy)))
          slots)))

(defn run-review-loop
  "Drive the bounded review→revise→re-review loop for one frozen-but-not-yet-
   synced grid. Returns {:grid :review :reviews :revision-rejected?}.

   `review-fn`/`revise-fn` are the injected Tunabrain calls (default the real
   `tb/review-schedule!`/`tb/revise-schedule!` — passed in by the caller). On a
   failing verdict it revises, then re-runs the deterministic feasibility check
   over `[hstart, hend)`: if the taste revision introduced a blocking shortfall
   the revision is rejected (we keep the prior, feasible grid) and the loop
   stops, since a feasible-but-bland grid beats an infeasible one. Bounded by
   `max-reviews` (default 2)."
  [{:keys [tunabrain channel-spec profile skeleton grid hstart hend
           review-fn revise-fn cost-tier max-reviews]
    :or   {cost-tier "balanced" max-reviews 2}}]
  (loop [grid grid, reviews 0, last-review nil]
    (let [sw     (sample-week grid profile)
          resp   (review-fn tunabrain
                            {:channel channel-spec :skeleton skeleton :grid grid
                             :sample-week sw :catalog-profile profile :cost-tier cost-tier})
          review (:review resp)]
      (log/info "schedule review"
                {:channel (:name channel-spec) :round reviews
                 :verdict (:verdict review) :score (:score review)
                 :findings (count (:findings review))})
      (if (or (= "pass" (:verdict review)) (>= reviews max-reviews))
        {:grid grid :review review :reviews reviews}
        (let [revised (:grid (revise-fn tunabrain
                                        {:channel channel-spec :catalog-profile profile
                                         :current-grid grid :review review :cost-tier cost-tier}))
              report  (feasibility/check revised profile (str hstart) (str hend))]
          (if (= "blocked" (:overall_status report))
            (do (log/warn "review revision rejected: reintroduced a feasibility shortfall; keeping prior grid"
                          {:channel (:name channel-spec) :round (inc reviews)})
                {:grid grid :review review :reviews (inc reviews) :revision-rejected? true})
            (recur revised (inc reviews) review)))))))
