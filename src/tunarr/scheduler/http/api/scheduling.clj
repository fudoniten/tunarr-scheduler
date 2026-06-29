(ns tunarr.scheduler.http.api.scheduling
  "HTTP handlers for periodic scheduling tasks.

   These endpoints are intended to be triggered by Kubernetes CronJobs (see
   deploy/k8s) rather than an in-process scheduler. Each runs the corresponding
   task against the live system components and returns a per-channel summary.

   Quarterly and monthly tasks are submitted as async jobs (202 + job ID)
   because they make heavy LLM calls via Tunabrain that can take several
   minutes per channel.

   All endpoints accept optional repeatable selectors to limit the run to
   specific channels: ?channel=key (the config key name) and/or
   ?channel_id=uuid (the channel's ::media/channel-id). When omitted, all
   configured channels are processed. Any other query param, or a selector that
   matches no configured channel, is rejected with a 400 before any work runs."
  (:require [clojure.string :as str]
            [taoensso.timbre :as log]
            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.scheduling.tasks :as tasks]
            [tunarr.scheduler.jobs.runner :as jobs]
            [tunarr.scheduler.http.util :as util]))

;; ---------------------------------------------------------------------------
;; Channel selection
;;
;; The ?channel param arrives as a string, but channel config keys are
;; keywords, so we match ?channel on (name k) rather than the raw key —
;; otherwise a string param never matches a keyword key, the channel map is
;; silently emptied, and the task runs against zero channels (no Tunabrain
;; call). ?channel_id matches the channel's ::media/channel-id (stringified).
;; ---------------------------------------------------------------------------

(defn- param->set
  "Normalize a coerced query param that may be a single string or a vector of
   strings into a set of strings (nil when the param is absent)."
  [v]
  (when (some? v)
    (set (if (sequential? v) v [v]))))

(defn- channel-selectors
  "The requested channel selectors from the coerced query params:
   {:names #{…from ?channel…} :ids #{…from ?channel_id…}}. Either may be nil."
  [req]
  {:names (param->set (get-in req [:parameters :query :channel]))
   :ids   (param->set (get-in req [:parameters :query :channel_id]))})

(defn- selecting? [{:keys [names ids]}]
  (boolean (or names ids)))

(defn- channel-id-str [cfg]
  (some-> (::media/channel-id cfg) str))

(defn- channel-matches? [{:keys [names ids]} channel-key cfg]
  (or (and names (contains? names (name channel-key)))
      (and ids   (contains? ids (channel-id-str cfg)))))

(defn- filter-channels
  "Return ctx with :channels narrowed to those matching the selectors, or
   unchanged when no selector is present."
  [ctx selectors]
  (if (selecting? selectors)
    (update ctx :channels
            (fn [chs]
              (into {} (filter (fn [[k cfg]] (channel-matches? selectors k cfg))) chs)))
    ctx))

(defn- unknown-selectors
  "Selector values that match no configured channel, rendered as human-readable
   strings (\"channel=foo\" / \"channel_id=…\"). nil when every selector
   resolves to a channel."
  [ctx {:keys [names ids]}]
  (let [chs         (:channels ctx)
        known-names (set (map name (keys chs)))
        known-ids   (into #{} (keep (comp channel-id-str val)) chs)]
    (seq (concat (map #(str "channel=" %)    (sort (remove known-names (or names #{}))))
                 (map #(str "channel_id=" %) (sort (remove known-ids   (or ids   #{}))))))))

(defn- unknown-params
  "Provided query-param names that aren't in `allowed` (a set of strings).
   Reads the raw, un-coerced :query-params (populated by parameters-middleware)
   so typo'd/unexpected params are caught here rather than silently stripped by
   request coercion."
  [allowed req]
  (seq (sort (remove allowed (keys (:query-params req))))))

(defn- bad-request [msg]
  {:status 400 :body {:error msg}})

(def ^:private channel-params #{"channel" "channel_id"})
(def ^:private daily-params (conj channel-params "horizon"))

(defn- with-selected-channels
  "Validate the request's query params and channel selectors, then call
   (f ctx') with the channel-filtered ctx. Returns a 400 instead when an
   unrecognized query param or an unresolvable channel selector is present —
   failing fast before any job is launched."
  [ctx allowed-params req f]
  (if-let [bad (unknown-params allowed-params req)]
    (bad-request (str "unrecognized query param(s): " (str/join ", " bad)
                      ". Allowed: " (str/join ", " (sort allowed-params)) "."))
    (let [selectors (channel-selectors req)]
      (if-let [unknown (unknown-selectors ctx selectors)]
        (bad-request (str "unknown channel(s): " (str/join ", " unknown)
                          ". Check the configured channel keys/ids."))
        (f (filter-channels ctx selectors))))))

(defn daily-handler
  "POST /api/scheduling/daily — extend the playout horizon for every channel.
   Optional ?horizon=N (days, default 14). Optional ?channel=key /
   ?channel_id=uuid to limit."
  [ctx]
  (fn [req]
    (try
      (with-selected-channels ctx daily-params req
        (fn [ctx']
          (let [horizon (get-in req [:parameters :query :horizon] 14)
                results (tasks/run-daily! ctx' :horizon horizon)]
            {:status 200 :body {:task "daily" :horizon horizon :results results}})))
      (catch Exception e
        (log/error e "daily scheduling task failed")
        {:status 500 :body {:error (util/error-message e)}}))))

(defn weekly-handler
  "POST /api/scheduling/weekly — expand each channel's grid + overrides for the
   coming week and push the DailySlots to Pseudovision.
   Optional ?channel=key / ?channel_id=uuid to limit."
  [ctx]
  (fn [req]
    (try
      (with-selected-channels ctx channel-params req
        (fn [ctx']
          {:status 200 :body {:task "weekly" :results (tasks/run-weekly! ctx')}}))
      (catch Exception e
        (log/error e "weekly scheduling task failed")
        {:status 500 :body {:error (util/error-message e)}}))))

(defn monthly-handler
  "POST /api/scheduling/monthly — propose + store sparse monthly overrides for
   every channel against their frozen grids. Returns 202 with a job ID.
   Optional ?channel=key / ?channel_id=uuid to limit."
  [ctx]
  (fn [req]
    (with-selected-channels ctx channel-params req
      (fn [ctx']
        (let [job (jobs/submit-job!
                   (:job-runner ctx)
                   {:type :media/scheduling-monthly}
                   (fn [_report-progress]
                     (tasks/run-monthly! ctx')))]
          {:status 202 :body {:task "monthly" :job job}})))))

(defn quarterly-handler
  "POST /api/scheduling/quarterly — propose → check → repair → freeze the
   quarterly grid for every channel. Returns 202 with a job ID.
   Optional ?channel=key / ?channel_id=uuid to limit."
  [ctx]
  (fn [req]
    (with-selected-channels ctx channel-params req
      (fn [ctx']
        (let [job (jobs/submit-job!
                   (:job-runner ctx)
                   {:type :media/scheduling-quarterly}
                   (fn [_report-progress]
                     (tasks/run-quarterly! ctx')))]
          {:status 202 :body {:task "quarterly" :job job}})))))
