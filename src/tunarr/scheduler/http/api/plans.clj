(ns tunarr.scheduler.http.api.plans
  "HTTP handlers giving the UI read access to the layered-grid plans (frozen
  quarterly grids, monthly overrides), a weekly schedule preview, and the
  per-channel operator guidance (the manual input surface that feeds
  generation). None of these gate generation.

  The SQL executor is obtained from the catalog component (ctx :catalog
  → :executor), as in the strategy handlers.

  URL channels (`:channel` path param) are the config **slug** (e.g. 'goldenreels')
  — the same identifier used by the POST `/api/scheduling/{daily,weekly,...}`
  endpoints. Storage keys on the **TS `channel.id` UUID** (e.g.
  'e2d423d2-f373-49fa-8c2a-b2ea1ed8c144'). The URL-channel → storage-UUID
  translation is done by `channel-storage-uuid` so that lookups by slug
  succeed for data that was originally stored by UUID. The TS `channel.id`
  UUID can also be passed directly via `?channel_id=<uuid>` (skips the
  slug lookup)."
  (:require [clojure.string :as str]
            [taoensso.timbre :as log]
            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.scheduling.storage :as storage]
            [tunarr.scheduler.scheduling.plans :as plans]
            [tunarr.scheduler.http.util :as util]))

(defn- executor-of [ctx] (:executor (:catalog ctx)))

(defn- channel-storage-uuid
  "Translate a URL `:channel` param to the storage key (the TS
   `channel.id` UUID, e.g. 'e2d423d2-f373-49fa-8c2a-b2ea1ed8c144').
   - If `channel` matches a configured channel key (e.g. 'goldenreels'),
     return the `::media/channel-uuid` from that config entry.
   - If `channel` already looks like a UUID (36 chars, 4 dashes), return
     it as-is so direct UUID lookups work.
   - If `channel` matches a configured `::media/channel-fullname`
     (case-insensitive), resolve via that.
   - Otherwise, look the UUID up in the `channel` table by `full_name`
     or `name` (case-insensitive) — the configmap may not carry
     `::media/channel-uuid` yet, and the DB is the source of truth.
   - Returns `nil` as a last-ditch signal that no resolution was possible;
     handlers will then return a 404 with a clear error rather than
     querying storage with an untranslated key."
  [ex ctx channel]
  (or (some-> (get-in ctx [:channels (keyword channel)])
              (::media/channel-uuid))
      (when (and channel (re-matches #"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" channel))
        channel)
      (some->> (get-in ctx [:channels])
               vals
               (some (fn [cfg]
                       (when (= (str/lower-case (::media/channel-fullname cfg))
                                (str/lower-case channel))
                         (::media/channel-uuid cfg)))))
      (storage/find-channel-id ex channel)))

(defn- resolve-channel-params
  "Extract the channel UUID and display name from the request. Honors
   `?channel_id=<uuid>` (direct UUID) and the `:channel` path param (slug
   or display name). Returns a map with `:channel-uuid` and `:channel-name`
   (the display name) so handlers can include the human-readable name in
   responses without an extra DB round-trip.

   When the URL value is a slug and the configmap doesn't carry
   `::media/channel-uuid`, the UUID is looked up in the `channel` table.
   The display name is then read from the same row, so the response stays
   self-describing (e.g. `Enigma TV` rather than `enigma`)."
  [ex ctx req]
  (let [path-channel   (get-in req [:parameters :path :channel])
        query-uuid     (get-in req [:parameters :query :channel_id])
        path-uuid      (when path-channel (channel-storage-uuid ex ctx path-channel))
        chosen-uuid    (or query-uuid path-uuid)
        ;; The slug-lookup is the common case (the URL contains a config
        ;; key like 'goldenreels'). The UUID-iterating fallback handles
        ;; the case where the URL is `?channel_id=<uuid>` or the URL
        ;; contains a UUID directly.
        cfg            (or (when path-channel
                             (get-in ctx [:channels (keyword path-channel)]))
                           (some (fn [[_ c]]
                                   (when (= (::media/channel-uuid c) chosen-uuid) c))
                                 (:channels ctx)))
        ;; Display name: configmap first, then DB row (in case the
        ;; channel was resolved via the DB fallback), then the raw
        ;; URL value as a last resort.
        display-name   (or (and cfg (::media/channel-fullname cfg))
                           (when chosen-uuid (storage/find-channel-full-name ex chosen-uuid))
                           path-channel)]
    {:channel-uuid chosen-uuid
     :channel-name display-name}))

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
  feasibility snapshot). Optional ?quarter=Q1&year=2026; defaults to today's.
  Optional ?channel_id=<uuid> for direct UUID lookup."
  [ctx]
  (let [ex (executor-of ctx)]
    (fn [req]
      (with-handler "Error getting grid"
        (let [{:keys [channel-uuid channel-name]} (resolve-channel-params ex ctx req)
              today   (plans/today)
              quarter (get-in req [:parameters :query :quarter] (plans/quarter-of today))
              year    (get-in req [:parameters :query :year] (plans/year-of today))]
          (if-let [g (storage/current-grid ex channel-uuid quarter year)]
            {:status 200 :body (assoc g :channel-name channel-name)}
            {:status 404 :body {:error (format "No frozen grid for %s %s %d"
                                              (or channel-name channel-uuid) quarter year)}}))))))

(defn list-grids-handler
  "GET /api/scheduling/channels/:channel/grids — grid version history.
  Optional ?channel_id=<uuid> for direct UUID lookup."
  [ctx]
  (let [ex (executor-of ctx)]
    (fn [req]
      (with-handler "Error listing grids"
        (let [{:keys [channel-uuid]} (resolve-channel-params ex ctx req)]
          {:status 200 :body {:grids (storage/list-grids ex :channel channel-uuid)}})))))

;; ---------------------------------------------------------------------------
;; Overrides (monthly plans)
;; ---------------------------------------------------------------------------

(defn get-overrides-handler
  "GET /api/scheduling/channels/:channel/overrides — current overrides for a
  month. Optional ?month=YYYY-MM; defaults to the current month.
  Optional ?channel_id=<uuid> for direct UUID lookup."
  [ctx]
  (let [ex (executor-of ctx)]
    (fn [req]
      (with-handler "Error getting overrides"
        (let [{:keys [channel-uuid channel-name]} (resolve-channel-params ex ctx req)
              month   (get-in req [:parameters :query :month] (plans/month-of (plans/today)))]
          (if-let [o (storage/current-overrides ex channel-uuid month)]
            {:status 200 :body (assoc o :channel-name channel-name)}
            {:status 404 :body {:error (format "No overrides for %s %s"
                                              (or channel-name channel-uuid) month)}}))))))

(defn list-overrides-handler
  "GET /api/scheduling/channels/:channel/overrides/history — override history.
  Optional ?channel_id=<uuid> for direct UUID lookup."
  [ctx]
  (let [ex (executor-of ctx)]
    (fn [req]
      (with-handler "Error listing overrides"
        (let [{:keys [channel-uuid]} (resolve-channel-params ex ctx req)]
          {:status 200 :body {:overrides (storage/list-overrides ex :channel channel-uuid)}})))))

;; ---------------------------------------------------------------------------
;; Schedule preview (expander; no Tunabrain call) + combined dashboard
;; ---------------------------------------------------------------------------

(defn preview-handler
  "GET /api/scheduling/channels/:channel/preview — expand the current grid +
  overrides over [start, end). Optional ?start=YYYY-MM-DD&end=YYYY-MM-DD;
  defaults to the next 7 days. Optional ?channel_id=<uuid>."
  [ctx]
  (let [ex (executor-of ctx)]
    (fn [req]
      (with-handler "Error building schedule preview"
        (let [{:keys [channel-uuid]} (resolve-channel-params ex ctx req)
              today   (plans/today)
              start   (get-in req [:parameters :query :start] (str today))
              end     (get-in req [:parameters :query :end] (str (.plusDays today 7)))]
          {:status 200 :body (plans/preview ex channel-uuid start end)})))))

(defn dashboard-handler
  "GET /api/scheduling/channels/:channel/plan — combined view: current grid (+
  feasibility), current overrides, and operator guidance.
  Optional ?channel_id=<uuid>."
  [ctx]
  (let [ex (executor-of ctx)]
    (fn [req]
      (with-handler "Error building channel plan"
        (let [{:keys [channel-uuid]} (resolve-channel-params ex ctx req)]
          {:status 200 :body (plans/dashboard ex channel-uuid)})))))

;; ---------------------------------------------------------------------------
;; Operator guidance (manual input)
;; ---------------------------------------------------------------------------

(def ^:private empty-guidance
  {:strategic_guidance nil :quarterly_theme nil :monthly_theme nil :planned_events []})

(defn get-guidance-handler
  "GET /api/scheduling/channels/:channel/guidance — operator guidance (an empty
  shape when none has been set). Optional ?channel_id=<uuid>."
  [ctx]
  (let [ex (executor-of ctx)]
    (fn [req]
      (with-handler "Error getting guidance"
        (let [{:keys [channel-uuid channel-name]} (resolve-channel-params ex ctx req)]
          {:status 200 :body (or (storage/get-guidance ex channel-uuid)
                                 (assoc empty-guidance
                                        :channel channel-uuid
                                        :channel-name channel-name))})))))

(defn put-guidance-handler
  "PUT /api/scheduling/channels/:channel/guidance — set/update operator guidance.
  Body may contain any of :strategic_guidance, :quarterly_theme, :monthly_theme,
  :planned_events; absent fields are left unchanged.
  Optional ?channel_id=<uuid>."
  [ctx]
  (let [ex (executor-of ctx)]
    (fn [req]
      (with-handler "Error setting guidance"
        (let [{:keys [channel-uuid channel-name]} (resolve-channel-params ex ctx req)
              fields  (get-in req [:parameters :body])]
          {:status 200 :body
           (assoc (storage/set-guidance! ex channel-uuid fields)
                  :channel-name channel-name)})))))
