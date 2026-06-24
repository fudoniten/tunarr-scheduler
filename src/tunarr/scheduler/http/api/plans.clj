(ns tunarr.scheduler.http.api.plans
  "HTTP handlers giving the UI read access to the layered-grid plans (frozen
   quarterly grids, monthly overrides), a weekly schedule preview, and the
   per-channel operator guidance (the manual-input surface that feeds
   generation). None of these gate generation.

   The SQL executor is obtained from the catalog component (ctx :catalog
   → :executor), as in the strategy handlers."
  (:require [taoensso.timbre :as log]
            [tunarr.scheduler.scheduling.storage :as storage]
            [tunarr.scheduler.scheduling.plans :as plans]))

(defn- executor-of [ctx] (:executor (:catalog ctx)))

(defmacro ^:private with-handler
  "Wrap a handler body with the standard try/500 envelope."
  [msg & body]
  `(try ~@body
        (catch Exception e#
          (log/error e# ~msg)
          {:status 500 :body {:error (.getMessage e#)}})))

;; ---------------------------------------------------------------------------
;; Channels with plans
;; ---------------------------------------------------------------------------

(defn list-channels-handler
  "GET /api/scheduling/channels — channels that have any stored plan or guidance."
  [ctx]
  (let [ex (executor-of ctx)]
    (fn [_]
      (with-handler "Error listing planned channels"
        {:status 200 :body {:channels (storage/planned-channels ex)}}))))

;; ---------------------------------------------------------------------------
;; Grids (quarterly plans)
;; ---------------------------------------------------------------------------

(defn get-grid-handler
  "GET /api/scheduling/channels/:channel/grid — the current frozen grid (with its
   feasibility snapshot). Optional ?quarter=Q1&year=2026; defaults to today's."
  [ctx]
  (let [ex (executor-of ctx)]
    (fn [req]
      (with-handler "Error getting grid"
        (let [channel (get-in req [:parameters :path :channel])
              today   (plans/today)
              quarter (get-in req [:parameters :query :quarter] (plans/quarter-of today))
              year    (get-in req [:parameters :query :year] (plans/year-of today))]
          (if-let [g (storage/current-grid ex channel quarter year)]
            {:status 200 :body g}
            {:status 404 :body {:error (format "No frozen grid for %s %s %d" channel quarter year)}}))))))

(defn list-grids-handler
  "GET /api/scheduling/channels/:channel/grids — grid version history."
  [ctx]
  (let [ex (executor-of ctx)]
    (fn [req]
      (with-handler "Error listing grids"
        (let [channel (get-in req [:parameters :path :channel])]
          {:status 200 :body {:grids (storage/list-grids ex :channel channel)}})))))

;; ---------------------------------------------------------------------------
;; Overrides (monthly plans)
;; ---------------------------------------------------------------------------

(defn get-overrides-handler
  "GET /api/scheduling/channels/:channel/overrides — current overrides for a
   month. Optional ?month=YYYY-MM; defaults to the current month."
  [ctx]
  (let [ex (executor-of ctx)]
    (fn [req]
      (with-handler "Error getting overrides"
        (let [channel (get-in req [:parameters :path :channel])
              month   (get-in req [:parameters :query :month] (plans/month-of (plans/today)))]
          (if-let [o (storage/current-overrides ex channel month)]
            {:status 200 :body o}
            {:status 404 :body {:error (format "No overrides for %s %s" channel month)}}))))))

(defn list-overrides-handler
  "GET /api/scheduling/channels/:channel/overrides/history — override history."
  [ctx]
  (let [ex (executor-of ctx)]
    (fn [req]
      (with-handler "Error listing overrides"
        (let [channel (get-in req [:parameters :path :channel])]
          {:status 200 :body {:overrides (storage/list-overrides ex :channel channel)}})))))

;; ---------------------------------------------------------------------------
;; Schedule preview (expander; no Tunabrain call) + combined dashboard
;; ---------------------------------------------------------------------------

(defn preview-handler
  "GET /api/scheduling/channels/:channel/preview — expand the current grid +
   overrides over [start, end). Optional ?start=YYYY-MM-DD&end=YYYY-MM-DD;
   defaults to the next 7 days."
  [ctx]
  (let [ex (executor-of ctx)]
    (fn [req]
      (with-handler "Error building schedule preview"
        (let [channel (get-in req [:parameters :path :channel])
              today   (plans/today)
              start   (get-in req [:parameters :query :start] (str today))
              end     (get-in req [:parameters :query :end] (str (.plusDays today 7)))]
          {:status 200 :body (plans/preview ex channel start end)})))))

(defn dashboard-handler
  "GET /api/scheduling/channels/:channel/plan — combined view: current grid (+
   feasibility), current overrides, and operator guidance."
  [ctx]
  (let [ex (executor-of ctx)]
    (fn [req]
      (with-handler "Error building channel plan"
        (let [channel (get-in req [:parameters :path :channel])]
          {:status 200 :body (plans/dashboard ex channel)})))))

;; ---------------------------------------------------------------------------
;; Operator guidance (manual input)
;; ---------------------------------------------------------------------------

(def ^:private empty-guidance
  {:strategic_guidance nil :quarterly_theme nil :monthly_theme nil :planned_events []})

(defn get-guidance-handler
  "GET /api/scheduling/channels/:channel/guidance — operator guidance (an empty
   shape when none has been set)."
  [ctx]
  (let [ex (executor-of ctx)]
    (fn [req]
      (with-handler "Error getting guidance"
        (let [channel (get-in req [:parameters :path :channel])]
          {:status 200 :body (or (storage/get-guidance ex channel)
                                 (assoc empty-guidance :channel channel))})))))

(defn put-guidance-handler
  "PUT /api/scheduling/channels/:channel/guidance — set/update operator guidance.
   Body may contain any of :strategic_guidance, :quarterly_theme, :monthly_theme,
   :planned_events; absent fields are left unchanged."
  [ctx]
  (let [ex (executor-of ctx)]
    (fn [req]
      (with-handler "Error setting guidance"
        (let [channel (get-in req [:parameters :path :channel])
              fields  (get-in req [:parameters :body])]
          {:status 200 :body (storage/set-guidance! ex channel fields)})))))
