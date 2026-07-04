(ns tunarr.scheduler.http.api.plans
  "HTTP handlers giving the UI read access to the layered-grid plans (frozen
   quarterly grids, monthly overrides), a weekly schedule preview, and the
   per-channel operator guidance (the manual-input surface that feeds
   generation). None of these gate generation.

   The SQL executor is obtained from the catalog component (ctx :catalog
   → :executor), as in the strategy handlers.

   URL channels (`:channel` path param) are the config **slug** (e.g. 'goldenreels')
   — the same identifier used by the POST `/api/scheduling/{daily,weekly,...}`
   endpoints. Storage, however, keys on the **display name** (e.g. 'Golden Reels')
   per `tunarr.scheduler.scheduling.tasks` (where the cron tasks translate slug
   → fullname before persisting). Every handler in this namespace calls
   `channel-storage-key` to bridge that gap, so that lookups by slug succeed
   for data that was originally stored by display name. Looking up by display
   name directly is still supported (for backwards compatibility and direct
   one-off queries)."
  (:require [taoensso.timbre :as log]
            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.scheduling.storage :as storage]
            [tunarr.scheduler.scheduling.plans :as plans]
            [tunarr.scheduler.http.util :as util]))

(defn- executor-of [ctx] (:executor (:catalog ctx)))

(defn- channel-storage-key
  "Translate a URL `:channel` param to the storage key (display name).
   - If `channel` matches a configured channel key, return its fullname.
   - Otherwise return `channel` unchanged, so a direct lookup by display
     name still resolves (the case-mismatch symptom of pre-fix read paths)."
  [ctx channel]
  (or (some-> (get-in ctx [:channels (keyword channel)])
              (::media/channel-fullname))
      channel))

(defmacro ^:private with-handler
  "Wrap a handler body with the standard try/500 envelope."
  [msg & body]
  `(try ~@body
        (catch Exception e#
          (log/error e# ~msg)
          {:status 500 :body {:error (util/error-message e#)}})))

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
        (let [channel (channel-storage-key ctx (get-in req [:parameters :path :channel]))
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
        (let [channel (channel-storage-key ctx (get-in req [:parameters :path :channel]))]
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
        (let [channel (channel-storage-key ctx (get-in req [:parameters :path :channel]))
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
        (let [channel (channel-storage-key ctx (get-in req [:parameters :path :channel]))]
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
        (let [channel (channel-storage-key ctx (get-in req [:parameters :path :channel]))
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
        (let [channel (channel-storage-key ctx (get-in req [:parameters :path :channel]))]
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
        (let [channel (channel-storage-key ctx (get-in req [:parameters :path :channel]))]
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
        (let [channel (channel-storage-key ctx (get-in req [:parameters :path :channel]))
              fields  (get-in req [:parameters :body])]
          {:status 200 :body (storage/set-guidance! ex channel fields)})))))

;; ---------------------------------------------------------------------------
;; Content policy (deterministic hard constraints, e.g. watersheds)
;; ---------------------------------------------------------------------------

(defn get-policy-handler
  "GET /api/scheduling/channels/:channel/policy — the deterministic content
   policy (an empty watershed set when none has been set)."
  [ctx]
  (let [ex (executor-of ctx)]
    (fn [req]
      (with-handler "Error getting policy"
        (let [channel (channel-storage-key ctx (get-in req [:parameters :path :channel]))]
          {:status 200 :body (or (storage/get-policy ex channel)
                                 {:channel channel :watersheds []})})))))

(defn put-policy-handler
  "PUT /api/scheduling/channels/:channel/policy — set/replace the content policy.
   Body is a ContentPolicy ({:watersheds [...]}) validated before it is stored;
   an invalid policy is rejected with 400 rather than persisted."
  [ctx]
  (let [ex (executor-of ctx)]
    (fn [req]
      (with-handler "Error setting policy"
        (let [channel (channel-storage-key ctx (get-in req [:parameters :path :channel]))
              policy  (get-in req [:parameters :body])]
          (try
            {:status 200 :body (storage/set-policy! ex channel policy)}
            (catch clojure.lang.ExceptionInfo e
              (if (:errors (ex-data e))
                {:status 400 :body {:error (util/error-message e)
                                    :details (:errors (ex-data e))}}
                (throw e)))))))))
