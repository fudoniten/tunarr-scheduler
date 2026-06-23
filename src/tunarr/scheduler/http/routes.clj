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
            [tunarr.scheduler.http.api.intent       :as intent]
            [tunarr.scheduler.http.api.strategy     :as strategy]))

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
                  :query s/KindQuery}
     :get        {:summary   "Get all media items in a library with process timestamps. Supports optional ?kind parameter to filter by media kind (e.g., filler)."
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
     :post       {:summary   "Trigger async LLM retagging job. Supports optional ?force=true and ?kind=<type> parameters."
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

   ["/api/genres"
    {:tags ["browse"]
     :get  {:summary   "List all genres"
            :responses {200 {:body s/GenreListResponse}
                        500 {:body s/APIError}}
            :handler   (browse/list-genres-handler ctx)}}]

   ["/api/genres/:genre/media"
    {:tags       ["browse"]
     :parameters {:path [:map [:genre s/GenreName]]}
     :get        {:summary   "List media items with a given genre"
                  :responses {200 {:body s/MediaListResponse}
                              500 {:body s/APIError}}
                  :handler   (browse/get-media-by-genre-handler ctx)}}]

   ["/api/catalog/channels"
    {:tags ["browse"]
     :get  {:summary   "List all channels in the catalog"
            :responses {200 {:body s/ChannelListResponse}
                        500 {:body s/APIError}}
            :handler   (browse/list-channels-handler ctx)}}]

   ["/api/catalog/channels/:channel-name/media"
    {:tags       ["browse"]
     :parameters {:path [:map [:channel-name s/ChannelName]]}
     :get        {:summary   "List media items assigned to a given channel"
                  :responses {200 {:body s/MediaListResponse}
                              500 {:body s/APIError}}
                  :handler   (browse/get-media-by-channel-handler ctx)}}]

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
                   :handler   (channels/get-schedule-handler ctx)}
      :post       {:summary   "Update channel schedule in Pseudovision"
                   :parameters {:body s/ChannelScheduleRequest}
                   :responses {200 {:body s/ChannelScheduleResponse}
                               500 {:body s/APIError}}
                   :handler   (channels/update-schedule-handler ctx)}}]

    ["/api/channels/:channel-id/apply-template"
     {:tags       ["channels"]
      :parameters {:path [:map [:channel-id s/ChannelId]]
                   :body s/ApplyTemplateRequest}
      :post       {:summary   "Apply a schedule template to a channel"
                   :responses {200 {:body s/ChannelScheduleResponse}
                               404 {:body s/APIError}
                               500 {:body s/APIError}}
                   :handler   (channels/apply-template-handler ctx)}}]

    ["/api/channels/all/apply-templates"
     {:tags ["channels"]
      :post {:summary   "Apply default templates to all channels"
             :responses {200 {:body s/ApplyAllTemplatesResponse}
                         500 {:body s/APIError}}
             :handler   (channels/apply-all-templates-handler ctx)}}]

    ;; ── Intent ───────────────────────────────────────────────────────────────
    ["/api/channels/:channel-id/intent"
     {:tags       ["intent"]
      :parameters {:path [:map [:channel-id s/ChannelId]]
                   :body s/IntentRequest}
      :post       {:summary   "Process a natural-language scheduling intent"
                   :responses {200 {:body s/IntentResponse}
                               400 {:body s/APIError}
                               422 {:body s/APIError}
                               500 {:body s/APIError}}
                   :handler   (intent/intent-handler ctx)}}]

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
