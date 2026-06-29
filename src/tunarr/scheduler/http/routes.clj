(ns tunarr.scheduler.http.routes
  "HTTP routes with OpenAPI documentation and Malli validation."
  (:require [reitit.ring                            :as ring]
            [reitit.openapi                         :as openapi]
            [reitit.swagger-ui                      :as swagger-ui]
            [reitit.coercion.malli                  :as malli-coercion]
            [reitit.ring.coercion                   :as rrc]
            [reitit.ring.middleware.parameters      :as parameters]
            [reitit.ring.middleware.muuntaja        :as muuntaja-mw]
            [tunarr.scheduler.http.middleware       :as mw]
            [tunarr.scheduler.http.schemas          :as s]
            [tunarr.scheduler.http.api.media        :as media]
            [tunarr.scheduler.http.api.channels     :as channels]
            [tunarr.scheduler.http.api.jobs         :as jobs]
            [tunarr.scheduler.http.api.browse       :as browse]
            [tunarr.scheduler.http.api.strategy     :as strategy]
            [tunarr.scheduler.http.api.scheduling   :as scheduling]
            [tunarr.scheduler.http.api.plans        :as plans]))

;; ---------------------------------------------------------------------------
;; Basic handlers
;; ---------------------------------------------------------------------------

(defn health-handler [_]
  {:status 200 :body {:status "ok"}})

(defn version-handler [_]
  {:status 200
   :body {:git-commit    (System/getenv "GIT_COMMIT")
          :git-timestamp (System/getenv "GIT_TIMESTAMP")
          :version-tag   (System/getenv "VERSION_TAG")}})

;; ---------------------------------------------------------------------------
;; Routes with OpenAPI metadata
;; ---------------------------------------------------------------------------

(defn routes [ctx]
  ["" 
   ;; ── OpenAPI spec ────────────────────────────────────────────────────────
   ["/openapi.json"
    {:get {:no-doc  true
           :openapi {:info {:title       "Tunarr Scheduler API"
                            :version     "0.1.0"
                            :description "Tunarr Scheduler REST API for media management and scheduling"}}
           :handler (openapi/create-openapi-handler)}}]

   ;; ── Health ──────────────────────────────────────────────────────────────
   ["/healthz"
    {:get {:tags      ["health"]
           :summary   "Health check endpoint"
           :responses {200 {:body s/Health}}
           :handler   health-handler}}]

   ;; ── Version ─────────────────────────────────────────────────────────────
   ["/api/version"
    {:get {:tags      ["meta"]
           :summary   "Build and version information"
           :responses {200 {:body s/Version}}
           :handler   version-handler}}]

   ;; ── Media ───────────────────────────────────────────────────────────────
   ["/api/media/libraries"
    {:tags ["media"]
     :get  {:summary   "List all media libraries from Pseudovision"
            :responses {200 {:body s/LibraryListResponse}
                        500 {:body s/APIError}}
            :handler   (media/list-libraries-handler ctx)}}]

   ["/api/media/sync-libraries"
    {:tags ["media"]
     :post {:summary   "Sync libraries from Pseudovision to catalog"
            :responses {200 {:body s/LibraryListResponse}
                        400 {:body s/APIError}
                        500 {:body s/APIError}}
            :handler   (media/sync-libraries-handler ctx)}}]

   ["/api/library/:library/media"
    {:tags       ["media"]
     :parameters {:path [:map [:library s/LibraryName]]
                  :query s/MediaListQuery}
     :get        {:summary   "Get all media items in a library with process timestamps. Supports optional ?kind parameter to filter by media kind (e.g., filler), and ?q to filter by a case-insensitive name/overview match."
                  :responses {200 {:body s/MediaListResponse}
                              404 {:body s/APIError}
                              500 {:body s/APIError}}
                  :handler   (media/get-library-media-handler ctx)}}]

   ["/api/media-item/:media-id"
    {:tags       ["media"]
     :parameters {:path [:map [:media-id s/MediaId]]}
     :get        {:summary   "Get a specific media item by ID (catalog ID or external/Jellyfin ID) with process timestamps"
                  :responses {200 {:body s/MediaItemResponse}
                              404 {:body s/APIError}
                              500 {:body s/APIError}}
                  :handler   (media/get-media-by-id-handler ctx)}}]

   ["/api/media-item/:media-id/process/:process/reset"
    {:tags       ["media"]
     :parameters {:path [:map [:media-id s/MediaId] [:process s/ProcessActionName]]}
     :delete     {:summary   "Reset the last-run timestamp for a process on a media item, allowing it to be re-processed"
                  :responses {200 {:body s/ProcessResetResponse}
                              400 {:body s/APIError}
                              404 {:body s/APIError}
                              500 {:body s/APIError}}
                  :handler   (media/delete-media-process-timestamp-handler ctx)}}]

   ["/api/media-item/:media-id/retag"
    {:tags       ["media"]
     :parameters {:path [:map [:media-id s/MediaId]]}
     :post       {:summary   "Retag a single media item via Tunabrain"
                  :responses {202 {:body s/MediaActionResponse}
                              404 {:body s/APIError}
                              500 {:body s/APIError}}
                  :handler   (media/retag-media-item-handler ctx)}}]

   ["/api/media-item/:media-id/recategorize"
    {:tags       ["media"]
     :parameters {:path [:map [:media-id s/MediaId]]}
     :post       {:summary   "Recategorize a single media item via Tunabrain"
                  :responses {202 {:body s/MediaActionResponse}
                              404 {:body s/APIError}
                              500 {:body s/APIError}}
                  :handler   (media/recategorize-media-item-handler ctx)}}]

   ["/api/media-item/:media-id/sync-pseudovision"
    {:tags       ["media"]
     :parameters {:path [:map [:media-id s/MediaId]]}
     :post       {:summary   "Sync a single media item's tags to Pseudovision"
                  :responses {200 {:body s/MediaActionResponse}
                              400 {:body s/APIError}
                              404 {:body s/APIError}
                              500 {:body s/APIError}}
                  :handler   (media/sync-media-item-pseudovision-handler ctx)}}]

   ["/api/media/:library/process/:process/reset"
    {:tags       ["media"]
     :parameters {:path [:map [:library s/LibraryName] [:process s/ProcessActionName]]}
     :delete     {:summary   "Clear last-run timestamps for a process across all media in a library"
                  :responses {200 {:body s/ProcessResetResponse}
                              400 {:body s/APIError}
                              500 {:body s/APIError}}
                  :handler   (media/delete-library-process-timestamps-handler ctx)}}]

   ["/api/media/:library/rescan"
    {:tags       ["media"]
     :parameters {:path [:map [:library s/LibraryName]]}
     :post       {:summary   "Trigger async library rescan job"
                  :responses {202 {:body s/JobSubmitResponse}
                              400 {:body s/APIError}
                              500 {:body s/APIError}}
                  :handler   (media/rescan-handler ctx)}}]

    ["/api/media/:library/retag"
     {:tags       ["media"]
      :parameters {:path  [:map [:library s/LibraryName]]
                   :query [:map 
                           [:force {:optional true} [:enum "true" "false"]]
                           [:kind {:optional true} :string]]}
      :post       {:summary   "DEPRECATED: Use /api/media/:library/recategorize instead. Old flat-tag retagging via Tunabrain /tags."
                   :responses {202 {:body s/JobSubmitResponse}
                               400 {:body s/APIError}
                               500 {:body s/APIError}}
                   :handler   (media/retag-handler ctx)}}]

   ["/api/media/:library/add-taglines"
    {:tags       ["media"]
     :parameters {:path [:map [:library s/LibraryName]]}
     :post       {:summary   "Generate taglines for library media with LLM"
                  :responses {202 {:body s/JobSubmitResponse}
                              400 {:body s/APIError}
                              500 {:body s/APIError}}
                  :handler   (media/tagline-handler ctx)}}]

   ["/api/media/:library/recategorize"
    {:tags       ["media"]
     :parameters {:path  [:map [:library s/LibraryName]]
                  :query s/ForceQuery}
     :post       {:summary   "Recategorize library media with LLM"
                  :responses {202 {:body s/JobSubmitResponse}
                              400 {:body s/APIError}
                              500 {:body s/APIError}}
                  :handler   (media/recategorize-handler ctx)}}]

   ["/api/media/:library/retag-episodes"
    {:tags       ["media"]
     :parameters {:path  [:map [:library s/LibraryName]]
                  :query s/ForceQuery}
     :post       {:summary   "Retag episode special flags with LLM"
                  :responses {202 {:body s/JobSubmitResponse}
                              400 {:body s/APIError}
                              500 {:body s/APIError}}
                  :handler   (media/retag-episodes-handler ctx)}}]

   ["/api/media/:library/sync-pseudovision-tags"
    {:tags       ["media"]
     :parameters {:path [:map [:library s/LibraryName]]}
     :post       {:summary   "Sync library tags to Pseudovision (async job)"
                  :responses {202 {:body s/JobSubmitResponse}
                              400 {:body s/APIError}
                              500 {:body s/APIError}}
                  :handler   (media/pseudovision-sync-handler ctx)}}]

   ["/api/media/migrate-to-pseudovision"
    {:tags ["media"]
     :post {:summary    "One-time migration from local catalog to Pseudovision"
            :parameters {:body s/MigrateToPseudovisionRequest}
            :responses  {200 {:body s/MigrationResponse}
                         500 {:body s/APIError}}
            :handler    (media/migrate-to-pseudovision-handler ctx)}}]

   ["/api/media/:library/sync-from-pseudovision"
    {:tags       ["media"]
     :parameters {:path [:map [:library s/LibraryName]]}
     :post       {:summary   "Sync media items from Pseudovision to catalog"
                  :responses {200 {:body s/SyncFromPseudovisionResponse}
                              400 {:body s/APIError}
                              500 {:body s/APIError}}
                  :handler   (media/sync-from-pseudovision-handler ctx)}}]

   ["/api/media/:library/migrate-catalog-ids"
    {:tags       ["media"]
     :parameters {:path [:map [:library s/LibraryName]]}
     :post       {:summary   "Migrate catalog IDs to use Pseudovision format"
                  :responses {200 {:body s/MigrateCatalogIdsResponse}
                              400 {:body s/APIError}
                              500 {:body s/APIError}}
                  :handler   (media/migrate-catalog-ids-handler ctx)}}]

   ["/api/media/tags/audit"
    {:tags       ["media"]
     :parameters {:query s/TagAuditQuery}
     :post       {:summary   "Trigger async LLM tag audit job. Deletes unsuitable tags unless ?dry-run=true; the report is available in the job result."
                  :responses {202 {:body s/JobSubmitResponse}
                              500 {:body s/APIError}}
                  :handler   (media/audit-tags-handler ctx)}}]

   ["/api/media/tags/triage"
    {:tags       ["media"]
     :parameters {:query s/TagTriageQuery}
     :post       {:summary   "Trigger async LLM tag governance triage job. Applies keep/remove/rename decisions (with usage context) unless ?dry-run=true; supports ?target-limit=N."
                  :responses {202 {:body s/JobSubmitResponse}
                              500 {:body s/APIError}}
                  :handler   (media/triage-tags-handler ctx)}}]

   ["/api/dimensions/clean"
    {:tags       ["media"]
     :parameters {:query s/DimensionCleanupQuery}
     :post       {:summary   "Trigger async job that removes dimension values outside the configured controlled vocabulary across all media. Reports without deleting when ?dry-run=true."
                  :responses {202 {:body s/JobSubmitResponse}
                              500 {:body s/APIError}}
                  :handler   (media/clean-dimensions-handler ctx)}}]

   ;; ── Browse ──────────────────────────────────────────────────────────────
   ["/api/tags"
    {:tags ["browse"]
     :get  {:summary   "List all tags with usage counts and example titles"
            :responses {200 {:body s/TagListResponse}
                        500 {:body s/APIError}}
            :handler   (browse/list-tags-handler ctx)}}]

   ["/api/tags/:tag/media"
    {:tags       ["browse"]
     :parameters {:path [:map [:tag s/TagName]]}
     :get        {:summary   "List media items with a given tag"
                  :responses {200 {:body s/MediaListResponse}
                              500 {:body s/APIError}}
                  :handler   (browse/get-media-by-tag-handler ctx)}}]

    ;; DEPRECATED: Hardcoded genre endpoints. Use /api/tags with genre:NAME.
    ["/api/genres"
     {:tags ["browse"]
      :get  {:summary   "DEPRECATED: Use /api/tags with genre:NAME filter. Old hardcoded genre list."
             :responses {200 {:body s/GenreListResponse}
                         500 {:body s/APIError}}
             :handler   (browse/list-genres-handler ctx)}}]

    ;; DEPRECATED: Hardcoded genre endpoint. Use /api/tags/:tag/media with genre:NAME.
    ["/api/genres/:genre/media"
     {:tags       ["browse"]
      :parameters {:path [:map [:genre s/GenreName]]}
      :get        {:summary   "DEPRECATED: Use /api/tags/:tag/media with genre:NAME. Old hardcoded genre filter."
                   :responses {200 {:body s/MediaListResponse}
                               500 {:body s/APIError}}
                   :handler   (browse/get-media-by-genre-handler ctx)}}]

    ;; DEPRECATED: Hardcoded channel endpoints. Use /api/tags with channel:NAME.
    ["/api/catalog/channels"
     {:tags ["browse"]
      :get  {:summary   "DEPRECATED: Use /api/tags with channel:NAME filter. Old hardcoded channel list."
             :responses {200 {:body s/ChannelListResponse}
                         500 {:body s/APIError}}
             :handler   (browse/list-channels-handler ctx)}}]

     ;; DEPRECATED: Hardcoded channel endpoint. Use /api/tags/:tag/media with channel:NAME.
     ["/api/catalog/channels/:channel-name/media"
      {:tags       ["browse"]
       :parameters {:path [:map [:channel-name s/ChannelName]]}
       :get        {:summary   "DEPRECATED: Use /api/tags/:tag/media with channel:NAME. Old hardcoded channel filter."
                    :responses {200 {:body s/MediaListResponse}
                                500 {:body s/APIError}}
                    :handler   (browse/get-media-by-channel-handler ctx)}}]

    ;; NEW: Dimension browsing endpoints
    ["/api/dimensions"
     {:tags ["browse"]
      :get  {:summary   "List all dimensions with value counts"
             :responses {200 {:body s/DimensionListResponse}
                         500 {:body s/APIError}}
             :handler   (browse/list-dimensions-handler ctx)}}]

    ["/api/dimensions/:dimension/values"
     {:tags       ["browse"]
      :parameters {:path [:map [:dimension s/DimensionName]]}
      :get        {:summary   "List all values for a dimension with usage counts"
                   :responses {200 {:body s/DimensionValueListResponse}
                               500 {:body s/APIError}}
                   :handler   (browse/get-dimension-values-handler ctx)}}]

    ["/api/dimensions/:dimension/values/:value/media"
     {:tags       ["browse"]
      :parameters {:path [:map 
                          [:dimension s/DimensionName]
                          [:value :string]]}
      :get        {:summary   "List all media with a specific dimension value"
                   :responses {200 {:body s/MediaListResponse}
                               500 {:body s/APIError}}
                   :handler   (browse/get-media-by-dimension-value-handler ctx)}}]

    ["/api/media/:media-id/categories"
     {:tags       ["media"]
      :parameters {:path [:map [:media-id s/MediaId]]}
      :get        {:summary   "Get all dimension categories for a media item"
                   :responses {200 {:body s/MediaCategoriesResponse}
                               404 {:body s/APIError}
                               500 {:body s/APIError}}
                   :handler   (browse/get-media-categories-handler ctx)}}]

    ;; ── Channels ────────────────────────────────────────────────────────────
   ["/api/channels/sync-pseudovision"
    {:tags ["channels"]
     :post {:summary   "Sync all channels to Pseudovision"
            :responses {200 {:body s/ChannelSyncResponse}
                        500 {:body s/APIError}}
            :handler   (channels/sync-channels-handler ctx)}}]

    ["/api/channels/:channel-id/schedule"
     {:tags       ["channels"]
      :parameters {:path [:map [:channel-id s/ChannelId]]}
      :get       {:summary   "Get current channel schedule from Pseudovision"
                   :parameters {:query [:map [:horizon {:optional true} [:int {:min 1 :max 365}]]]}
                   :responses {200 {:body s/ChannelScheduleInfoResponse}
                               404 {:body s/APIError}
                               500 {:body s/APIError}}
                   :handler   (channels/get-schedule-handler ctx)}}]

    ;; ── Strategy ────────────────────────────────────────────────────────────
    ["/api/strategies"
     {:tags ["strategies"]
      :get  {:summary   "List all scheduling strategies"
             :parameters {:query s/StrategyListQuery}
             :responses {200 {:body s/StrategyListResponse}}
             :handler   (strategy/list-strategies-handler ctx)}
      :post {:summary   "Generate a new strategy"
             :parameters {:body s/GenerateStrategyRequest}
             :responses {201 {:body s/Strategy}
                         400 {:body s/APIError}
                         500 {:body s/APIError}}
             :handler   (strategy/generate-strategy-handler ctx)}}]

    ["/api/strategies/current"
     {:tags ["strategies"]
      :get  {:summary   "Get the most recent strategy"
             :parameters {:query s/CurrentStrategyQuery}
             :responses {200 {:body s/Strategy}
                         404 {:body s/APIError}}
             :handler   (strategy/get-current-strategy-handler ctx)}}]

    ["/api/strategies/:id"
     {:tags       ["strategies"]
      :parameters {:path [:map [:id s/StrategyId]]}
      :get        {:summary   "Get a strategy by ID"
                   :responses {200 {:body s/Strategy}
                               404 {:body s/APIError}}
                   :handler   (strategy/get-strategy-handler ctx)}
      :delete     {:summary   "Delete a strategy"
                   :responses {200 {:body s/Strategy}
                               404 {:body s/APIError}
                               500 {:body s/APIError}}
                   :handler   (strategy/delete-strategy-handler ctx)}}]

    ["/api/strategies/:id/apply"
     {:tags       ["strategies"]
      :parameters {:path [:map [:id s/StrategyId]]}
      :post       {:summary   "Apply a strategy"
                   :responses {200 {:body s/Strategy}
                               404 {:body s/APIError}
                               500 {:body s/APIError}}
                   :handler   (strategy/apply-strategy-handler ctx)}}]

    ["/api/strategies/:id/reject"
     {:tags       ["strategies"]
      :parameters {:path [:map [:id s/StrategyId]]}
      :post       {:summary   "Reject a strategy"
                   :responses {200 {:body s/Strategy}
                               404 {:body s/APIError}
                               500 {:body s/APIError}}
                   :handler   (strategy/reject-strategy-handler ctx)}}]

    ["/api/strategies/:id/revert"
     {:tags       ["strategies"]
      :parameters {:path [:map [:id s/StrategyId]]}
      :post       {:summary   "Revert a strategy and restore the previous one"
                   :responses {200 {:body [:map {:closed false}
                                          [:reverted s/Strategy]
                                          [:restored {:optional true} [:maybe s/Strategy]]]}
                               404 {:body s/APIError}
                               500 {:body s/APIError}}
                   :handler   (strategy/revert-strategy-handler ctx)}}]

    ;; ── Scheduling tasks (triggered by k8s CronJobs) ─────────────────────────
    ["/api/scheduling/daily"
     {:tags ["scheduling"]
      :post {:summary    "Extend the playout horizon for every channel"
             :parameters {:query s/DailyTaskQuery}
             :responses  {200 {:body s/SchedulingTaskResponse}
                          500 {:body s/APIError}}
             :handler    (scheduling/daily-handler ctx)}}]

    ["/api/scheduling/weekly"
     {:tags ["scheduling"]
      :post {:summary   "Re-apply schedule templates to every channel"
             :responses {200 {:body s/SchedulingTaskResponse}
                         500 {:body s/APIError}}
             :handler   (scheduling/weekly-handler ctx)}}]

    ["/api/scheduling/monthly"
     {:tags ["scheduling"]
      :post {:summary    "Propose + store sparse monthly overrides for every channel (async)"
             :responses  {202 {:body s/SchedulingJobResponse}
                          500 {:body s/APIError}}
             :handler    (scheduling/monthly-handler ctx)}}]

    ["/api/scheduling/quarterly"
     {:tags ["scheduling"]
      :post {:summary    "Propose → check → repair → freeze the quarterly grid per channel (async)"
             :responses  {202 {:body s/SchedulingJobResponse}
                          500 {:body s/APIError}}
             :handler    (scheduling/quarterly-handler ctx)}}]

    ;; ── Layered-grid plans (UI read access + operator guidance) ──────────────
    ["/api/scheduling/channels"
     {:tags ["plans"]
      :get  {:summary   "List channels that have any stored plan or guidance"
             :responses {200 {:body s/PlannedChannelsResponse}
                         500 {:body s/APIError}}
             :handler   (plans/list-channels-handler ctx)}}]

    ["/api/scheduling/channels/:channel/grid"
     {:tags       ["plans"]
      :parameters {:path  [:map [:channel s/ChannelName]]
                   :query s/GridQuery}
      :get        {:summary   "Current frozen quarterly grid (with feasibility snapshot)"
                   :responses {200 {:body s/GridRecord}
                               404 {:body s/APIError}
                               500 {:body s/APIError}}
                   :handler   (plans/get-grid-handler ctx)}}]

    ["/api/scheduling/channels/:channel/grids"
     {:tags       ["plans"]
      :parameters {:path [:map [:channel s/ChannelName]]}
      :get        {:summary   "Quarterly grid version history for a channel"
                   :responses {200 {:body s/GridListResponse}
                               500 {:body s/APIError}}
                   :handler   (plans/list-grids-handler ctx)}}]

    ["/api/scheduling/channels/:channel/overrides"
     {:tags       ["plans"]
      :parameters {:path  [:map [:channel s/ChannelName]]
                   :query s/OverridesQuery}
      :get        {:summary   "Current monthly overrides for a channel"
                   :responses {200 {:body s/OverridesRecord}
                               404 {:body s/APIError}
                               500 {:body s/APIError}}
                   :handler   (plans/get-overrides-handler ctx)}}]

    ["/api/scheduling/channels/:channel/overrides/history"
     {:tags       ["plans"]
      :parameters {:path [:map [:channel s/ChannelName]]}
      :get        {:summary   "Monthly override version history for a channel"
                   :responses {200 {:body s/OverridesListResponse}
                               500 {:body s/APIError}}
                   :handler   (plans/list-overrides-handler ctx)}}]

    ["/api/scheduling/channels/:channel/preview"
     {:tags       ["plans"]
      :parameters {:path  [:map [:channel s/ChannelName]]
                   :query s/PreviewQuery}
      :get        {:summary   "Expand the current grid + overrides into DailySlots (no LLM)"
                   :responses {200 {:body s/SchedulePreviewResponse}
                               500 {:body s/APIError}}
                   :handler   (plans/preview-handler ctx)}}]

    ["/api/scheduling/channels/:channel/plan"
     {:tags       ["plans"]
      :parameters {:path [:map [:channel s/ChannelName]]}
      :get        {:summary   "Combined per-channel view: grid, overrides, guidance"
                   :responses {200 {:body s/ChannelPlanResponse}
                               500 {:body s/APIError}}
                   :handler   (plans/dashboard-handler ctx)}}]

    ["/api/scheduling/channels/:channel/guidance"
     {:tags       ["plans"]
      :parameters {:path [:map [:channel s/ChannelName]]}
      :get        {:summary   "Get the per-channel operator guidance"
                   :responses {200 {:body s/ChannelGuidance}
                               500 {:body s/APIError}}
                   :handler   (plans/get-guidance-handler ctx)}
      :put        {:summary    "Set/update the per-channel operator guidance"
                   :parameters {:body s/ChannelGuidanceUpdate}
                   :responses  {200 {:body s/ChannelGuidance}
                                500 {:body s/APIError}}
                   :handler    (plans/put-guidance-handler ctx)}}]

    ;; ── Jobs ────────────────────────────────────────────────────────────────
    ["/api/jobs"
     {:tags ["jobs"]
      :get  {:summary   "List all async jobs"
             :responses {200 {:body s/JobListResponse}}
             :handler   (jobs/list-jobs-handler ctx)}}]

    ["/api/jobs/:job-id"
     {:tags       ["jobs"]
      :parameters {:path [:map [:job-id s/JobId]]}
      :get        {:summary   "Get job status and details"
                   :responses {200 {:body s/JobInfoResponse}
                               404 {:body s/APIError}}
                   :handler   (jobs/get-job-handler ctx)}}]])

;; ---------------------------------------------------------------------------
;; Handler creation with middleware
;; ---------------------------------------------------------------------------

(defn handler
  "Create the ring handler with OpenAPI support.

   Route data supplies the canonical Reitit middleware chain - parameters,
   Muuntaja (JSON request decoding), the application exception handler, and
   malli coercion for :parameters / :responses. Routes without schemas still
   traverse the chain as a pass-through.

   Outer wraps - error handling, request logging, JSON response encoding -
   cover the entire dispatch tree so that unmatched routes (404/405) and
   the Swagger UI handler also go through them."
  [ctx]
  (let [router
        (ring/router
         (routes ctx)
         {;; Allow the static "/api/strategies/current" route to coexist with the
          ;; "/api/strategies/:id" wildcard route. Reitit's router resolves these
          ;; at runtime by preferring the exact/static match over the wildcard.
          :conflicts nil
          :data {:muuntaja   mw/muuntaja
                 :coercion   malli-coercion/coercion
                 :middleware [parameters/parameters-middleware
                              muuntaja-mw/format-negotiate-middleware
                              muuntaja-mw/format-request-middleware
                              muuntaja-mw/format-response-middleware
                              mw/exception-middleware
                              rrc/coerce-request-middleware
                              rrc/coerce-response-middleware]}})

        fallback-handler
        (ring/routes
         (swagger-ui/create-swagger-ui-handler
          {:path "/swagger-ui"
           :url  "/openapi.json"})
         (ring/create-default-handler
          {:not-found
           (fn [_]
             {:status 404
              :body   {:error "Not found"}})

           :method-not-allowed
           (fn [_]
             {:status 405
              :body   {:error "Method not allowed"}})}))

        dispatch
        (ring/ring-handler router fallback-handler)]
    (-> dispatch
        mw/wrap-json-response
        mw/wrap-request-logging
        mw/wrap-error-handler)))
