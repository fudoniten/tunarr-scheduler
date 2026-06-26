(ns tunarr.scheduler.tunabrain
  "HTTP client for interacting with the external tunabrain service."
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [clojure.pprint :refer [pprint]]

            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.scheduling.contracts :as contracts]

            [cheshire.core :as json]
            [clj-http.client :as http]
            [taoensso.timbre :as log]))

(defrecord TunabrainClient [endpoint http-opts]
  java.io.Closeable
  (close [_]
    (log/info "Closing tunabrain client")))

(defn- sanitize-endpoint [endpoint]
  (some-> endpoint (str/replace #"/+$" "")))

(defn- media-map->tunabrain-format
  "Transform media map from Clojure namespaced keywords to tunabrain's MediaItem format.
   See tunabrain api/models.py MediaItem for the canonical field list.
   When the media item is an episode, includes episode context fields."
  [media]
  (cond-> {:id              (::media/id media)
           :title           (::media/name media)
           :description     (::media/overview media)
           :genres          (mapv name (remove nil? (::media/genres media)))
           :current_tags    (mapv name (remove nil? (::media/tags media)))
           :rating          (::media/rating media)
           :critical_rating (::media/critic-rating media)
           :audience_rating (::media/community-rating media)}
    (::media/season-number media)
    (assoc :season_number (::media/season-number media))
    (::media/episode-number media)
    (assoc :episode_number (::media/episode-number media))
    (::media/parent-id media)
    (assoc :is_episode true)))

(defn- transform-category-value
  "Transform a category value from Clojure format to tunabrain format.
   Supports both plain keywords/strings and maps with :value and :description."
  [v]
  (cond
    (map? v)
    {:value (name (:value v))
     :description (:description v)}
    
    (keyword? v)
    (name v)
    
    :else
    (str v)))

(defn- categories->tunabrain-format
  "Transform categories from Clojure format to tunabrain's CategoryDefinition format.
   Each category should have :description and :values.
   Values can be plain keywords or maps with :value and :description."
  [categories]
  (into {}
        (map (fn [[category-name category-def]]
               [(name category-name)
                {:description (:description category-def)
                 :values (mapv transform-category-value (:values category-def))}]))
        categories))

(defn- json-post!
  [^TunabrainClient client path payload]
  (let [url (str (:endpoint client) path)
        request-opts (merge {:accept :json
                             :as :text
                             :headers (cond-> {"Content-Type" "application/json"})
                             :throw-exceptions false
                             :body (json/generate-string payload)}
                            (:http-opts client))]
    (log/info (format "connecting to %s, options: %s"
                      url (with-out-str (pprint request-opts))))
    (try
      (let [{:keys [status body]} (http/post url request-opts)]
        (if (<= 200 status 299)
          (json/parse-string body true)
          (let [error-details (try
                               (json/parse-string body true)
                               (catch Exception _
                                 body))]
            (log/error "Tunabrain request failed"
                      {:status status
                       :url url
                       :path path
                       :error-details error-details
                       :request-payload (json/parse-string (json/generate-string payload) true)})
            (throw (ex-info (format "tunabrain request failed: %s - %s" status error-details)
                           {:status status
                            :url url
                            :path path
                            :error-details error-details
                            :request-payload payload})))))
      (catch java.net.ConnectException e
        (throw (ex-info (format "connection refused to tunabrain at %s" url)
                        {:url url :path path :cause :connection-refused}
                        e)))
      (catch java.net.UnknownHostException e
        (throw (ex-info (format "unknown host when connecting to tunabrain at %s" url)
                        {:url url :path path :cause :unknown-host}
                        e)))
      (catch Exception e
        (throw (ex-info (format "error connecting to tunabrain at %s: %s"
                                url (.getMessage e))
                        {:url url :path path}
                        e))))))

;; DEPRECATED: Calls Tunabrain /tags which is deprecated. Flat tag generation
;; is not dimension-aware. Use request-categorization! (/categorize) instead.
;; See DIMENSION_CLEANUP.md for the full migration plan.
(defn request-tags!
  "DEPRECATED: Fetch flat tags for a media item from tunabrain.

  Calls the deprecated /tags endpoint which is not dimension-aware.
  Use request-categorization! (/categorize) instead.

  Payload should include the media data and any existing tags so the upstream
  service can deduplicate as needed."
  [client media & {:keys [catalog-tags]
                   :or   {catalog-tags []}}]
  (log/debug (format "===== RETAGGING MEDIA:\n%s\n" (with-out-str (pprint media))))
  (if-let [response (json-post! client "/tags"
                                {:media         (media-map->tunabrain-format media)
                                 :existing_tags (mapv name (remove nil? catalog-tags))})]
    (cond
      (s/valid? (s/coll-of string?) response)
      (do
        (log/info (format "Tag response: received %d tags" (count response)))
        {:tags (mapv keyword response)})

      (map? response)
      (let [{:keys [tags filtered_tags taglines]} response]
        (when (nil? tags)
          (throw (ex-info "Invalid tagging response: missing 'tags' key"
                          {:response response
                           :expected-keys [:tags]
                           :media-name (::media/name media)})))
        (log/info (format "Tag response: %d tags, %d filtered"
                          (count tags) (count filtered_tags)))
        {:tags          (mapv keyword tags)
         :filtered-tags (some->> filtered_tags (mapv keyword))
         :taglines      taglines})

      :else
      (throw (ex-info "Unexpected tagging response format"
                      {:response response
                       :media-name (::media/name media)})))
    (throw (ex-info "No response received from tunabrain tagging"
                    {:endpoint (:endpoint client)
                     :media-name (::media/name media)}))))

;; NOTE: Calls Tunabrain /tags/episode-special-flag. This is a constrained
;; vocabulary endpoint (christmas, crossover, musical) and is not the same as
;; the deprecated flat-tag /tags endpoint. It may be migrated to a dimension
;; model in the future.
(defn request-episode-special-flags!
  "Fetch special episode flags from tunabrain using constrained vocabulary.

   This is lightweight, cost-optimized tagging for episodes. Returns only
   flags from the allowed vocabulary (e.g., :christmas, :crossover, :musical)."
  [client media & {:keys [parent-title existing-flags]
                   :or   {existing-flags []}}]
  (log/debug (format "===== FLAGGING EPISODE:\\n%s\\n" (with-out-str (pprint media))))
  (if-let [response (json-post! client "/tags/episode-special-flag"
                                {:media (media-map->tunabrain-format media)
                                 :parent_title parent-title
                                 :existing_flags (mapv name (remove nil? existing-flags))})]
    (let [{:keys [flags]} response]
      (when (nil? flags)
        (throw (ex-info "Invalid episode flag response: missing 'flags' key"
                        {:response response
                         :expected-keys [:flags]
                         :media-name (::media/name media)})))
      (log/info (format "Episode flag response: %d flags for '%s'"
                        (count flags) (::media/name media)))
      {:flags (mapv keyword flags)})
    (throw (ex-info "No response received from tunabrain episode flagging"
                    {:endpoint (:endpoint client)
                     :media-name (::media/name media)}))))

(defn request-categorization!
  "Fetch dimension-based categorization from tunabrain."
  [client media & {:keys [categories]}]
  (if-let [response (json-post! client "/categorize"
                                {:media      (media-map->tunabrain-format media)
                                 :categories (categories->tunabrain-format categories)})]
    (let [{:keys [dimensions]} response]
      (when (nil? dimensions)
        (throw (ex-info "Invalid categorization response: missing 'dimensions' key"
                        {:response response
                         :expected-keys [:dimensions :mappings]
                         :media-name (::media/name media)})))
      (log/info (format "Categorization response: %d dimensions"
                        (count dimensions)))
      {:dimensions (into {}
                         (map (fn [{:keys [dimension values notes]}]
                                (let [notes-v (vec (or notes []))]
                                  [(keyword dimension)
                                   (map-indexed
                                    (fn [i v]
                                      {::media/category-value (keyword v)
                                       ::media/rationale      (or (get notes-v i) "")})
                                    values)])))
                         dimensions)})
    (throw (ex-info "No response received from tunabrain categorization"
                    {:endpoint (:endpoint client)
                     :media-name (::media/name media)}))))

;; NOTE: Calls Tunabrain /tag-governance/triage which is marked deprecated
;; in Tunabrain. Tag governance is still useful for hygiene but may be
;; migrated to a dimension-aware governance model in the future.
;; See DIMENSION_CLEANUP.md for the full migration plan.
(defn request-tag-triage!
  "Request governance recommendations for a collection of tags.

  Calls the deprecated /tag-governance/triage endpoint. Tag governance is
  still useful for hygiene but may be migrated to a dimension-aware model.

  Accepts a list of tag samples (maps with `:tag`, `:usage_count`, and
  `:example_titles`) and optional target limit and debug flags that mirror the
  upstream service."
  [client tag-samples & {:keys [target-limit debug]}]
  ;; Omit absent optional fields: upstream's `debug` is a non-nullable bool,
  ;; so sending an explicit null fails validation.
  (if-let [response (json-post! client "/tag-governance/triage"
                                (cond-> {:tags tag-samples}
                                  (some? target-limit) (assoc :target_limit target-limit)
                                  (some? debug)        (assoc :debug debug)))]
    (let [{:keys [decisions]} response]
      (when (nil? decisions)
        (throw (ex-info "Invalid tag triage response: missing 'decisions' key"
                        {:response response
                         :expected-keys [:decisions]
                         :tags-count (count tag-samples)})))
      (log/info (format "Tag triage response: %d decisions" (count decisions)))
      {:decisions (mapv #(update % :action keyword) decisions)})
    (throw (ex-info "No response received from tunabrain tag triage"
                    {:endpoint (:endpoint client)
                     :tags-count (count tag-samples)}))))

;; NOTE: Calls Tunabrain /tags/audit which is marked deprecated in Tunabrain.
;; Tag governance is still useful for hygiene but may be migrated to a
;; dimension-aware governance model in the future.
;; See DIMENSION_CLEANUP.md for the full migration plan.
(defn request-tag-audit!
  "Audit a list of tags for suitability and get removal recommendations.

  Calls the deprecated /tags/audit endpoint. Tag governance is still
  useful for hygiene but may be migrated to a dimension-aware model.

  Accepts a list of tags (strings or keywords) and returns a list of
  `{:tag ... :reason ...}` maps recommended for removal (see tunabrain
  api/models.py TagAuditResponse)."
  [client tags]
  (if-let [response (json-post! client "/tags/audit"
                                {:tags (mapv name tags)})]
    (let [{:keys [tags_to_delete]} response]
      (when (nil? tags_to_delete)
        (throw (ex-info "Invalid tag audit response: missing 'tags_to_delete' key"
                        {:response response
                         :expected-keys [:tags_to_delete]})))
      (log/info (format "Tag audit response: %d tags recommended for deletion"
                        (count tags_to_delete)))
      {:recommended-for-removal (vec tags_to_delete)})
    (throw (ex-info "No response received from tunabrain tag audit"
                    {:endpoint (:endpoint client)
                     :tags-count (count tags)}))))

;; ---------------------------------------------------------------------------
;; Layered-grid scheduling endpoints
;;
;; These hit Tunabrain's stateful-control-plane API (POST /api/scheduling/*).
;; Request payloads use the snake_case wire contracts mirrored in
;; tunarr.scheduler.scheduling.contracts. The request-builder fns are pure and
;; separated from the HTTP calls so they can be unit-tested. See handoff §5.
;; ---------------------------------------------------------------------------

(defn- channel->payload
  "The {name, description} channel stub Tunabrain expects."
  [channel]
  {:name (:name channel) :description (:description channel)})

(defn- warn-if-invalid
  "Log a warning (but pass the value through) when a Tunabrain response field
   does not conform to its contract — upstream is the source of truth, so we
   surface drift rather than rejecting it."
  [schema value label]
  (when (and value (contracts/humanize schema value))
    (log/warn "tunabrain response failed contract validation"
              {:field label :errors (contracts/humanize schema value)}))
  value)

(defn quarterly-grid-request
  "Build a QuarterlyGridRequest payload (handoff §5.1)."
  [{:keys [channel quarter year catalog-profile quarterly-theme strategic-guidance
           broadcast-day-start default-media-id cost-tier]
    :or   {broadcast-day-start "06:00" cost-tier "balanced"}}]
  {:channel             (channel->payload channel)
   :quarter             quarter
   :year                year
   :catalog_profile     catalog-profile
   :quarterly_theme     quarterly-theme
   :strategic_guidance  strategic-guidance
   :broadcast_day_start broadcast-day-start
   :default_media_id    default-media-id
   :cost_tier           cost-tier})

(defn repair-grid-request
  "Build a QuarterlyGridRepairRequest payload (handoff §5.2)."
  [{:keys [channel catalog-profile current-grid feasibility-report cost-tier]
    :or   {cost-tier "balanced"}}]
  {:channel            (channel->payload channel)
   :catalog_profile    catalog-profile
   :current_grid       current-grid
   :feasibility_report feasibility-report
   :cost_tier          cost-tier})

(defn monthly-overrides-request
  "Build a MonthlyOverridesRequest payload (handoff §5.3)."
  [{:keys [channel month grid catalog-profile monthly-theme planned-events
           strategic-guidance cost-tier]
    :or   {planned-events [] cost-tier "balanced"}}]
  {:channel            (channel->payload channel)
   :month              month
   :grid               grid
   :catalog_profile    catalog-profile
   :monthly_theme      monthly-theme
   :planned_events     (vec planned-events)
   :strategic_guidance strategic-guidance
   :cost_tier          cost-tier})

(defn propose-quarterly-grid!
  "Ask Tunabrain to propose a frozen quarterly Grid for one channel.
   `opts` are the keys consumed by `quarterly-grid-request`. Returns the parsed
   QuarterlyGridResponse: {:grid_id :status :grid :skeleton :warnings
   :cost_estimate :suggested_next_steps}."
  [client opts]
  (let [response (json-post! client "/api/scheduling/propose-quarterly-grid"
                             (quarterly-grid-request opts))]
    (when (nil? (:grid response))
      (throw (ex-info "tunabrain propose-quarterly-grid returned no grid"
                      {:response response})))
    (warn-if-invalid contracts/Grid (:grid response) :grid)
    response))

(defn repair-quarterly-grid!
  "Ask Tunabrain to repair a Grid against a FeasibilityReport. `opts` are the
   keys consumed by `repair-grid-request`. Returns the parsed
   QuarterlyGridRepairResponse: {:grid_id :status :grid :changes :cost_estimate}."
  [client opts]
  (let [response (json-post! client "/api/scheduling/repair-quarterly-grid"
                             (repair-grid-request opts))]
    (when (nil? (:grid response))
      (throw (ex-info "tunabrain repair-quarterly-grid returned no grid"
                      {:response response})))
    (warn-if-invalid contracts/Grid (:grid response) :grid)
    response))

(defn propose-monthly-overrides!
  "Ask Tunabrain to propose sparse monthly Overrides for a frozen Grid. `opts`
   are the keys consumed by `monthly-overrides-request`. Returns the parsed
   MonthlyOverridesResponse: {:overrides_id :status :month :overrides :warnings
   :cost_estimate :suggested_next_steps}. An empty :overrides list is normal."
  [client opts]
  (let [response (json-post! client "/api/scheduling/propose-monthly-overrides"
                             (monthly-overrides-request opts))]
    (when (nil? (:overrides response))
      (throw (ex-info "tunabrain propose-monthly-overrides returned no overrides list"
                      {:response response})))
    (doseq [o (:overrides response)]
      (warn-if-invalid contracts/ScheduleOverride o :override))
    response))

(defn create!
  "Create a tunabrain client from configuration.

  Supported keys:
  * `:endpoint` – base URL of the tunabrain proxy service (default
    `http://localhost:8080`).
  * `:http-opts` – additional `clj-http` options."
  [{:keys [endpoint http-opts]}]
  (let [endpoint (or (sanitize-endpoint endpoint) "http://localhost:8080")
        client (->TunabrainClient endpoint
                                  (or http-opts {}))]
    (log/info "Initialised tunabrain client" {:endpoint endpoint})
    client))
