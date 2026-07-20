(ns tunarr.scheduler.http.schemas
  "Malli schemas for request/response coercion and OpenAPI generation.
   
   These schemas serve as both validation rules and API documentation.
   Response schemas are open maps (malli's default) so new fields flow
   through automatically without silently stripping data.")

;; ---------------------------------------------------------------------------
;; Primitives
;; ---------------------------------------------------------------------------

(def LibraryName
  [:string {:min 1 :description "Library name (e.g. 'movies', 'shows')"}])

(def JobId
  [:string {:min 1 :description "Job identifier UUID"}])

(def ChannelId
  [:int {:min 1 :description "Channel identifier"}])

;; ---------------------------------------------------------------------------
;; Error envelopes
;; ---------------------------------------------------------------------------

(def APIError
  [:map
   [:error :string]])

(def CoercionError
  "Structured 400 returned when request coercion fails."
  [:map
   [:error    :string]
   [:in       [:vector :any]]
   [:humanized {:optional true} :any]])

;; ---------------------------------------------------------------------------
;; Common responses
;; ---------------------------------------------------------------------------

(def Health
  [:map
   [:status :string]])

(def Version
  [:map
   [:git-commit    {:optional true} [:maybe :string]]
   [:git-timestamp {:optional true} [:maybe :string]]
   [:version-tag   {:optional true} [:maybe :string]]])

(def BumperFile
  [:map
   [:path :string]
   [:name :string]
   [:size :int]
   [:modified :int]])

(def BumperListResponse
  [:map
   [:directory :string]
   [:count :int]
   [:files [:vector BumperFile]]])

(def BumperJobResponse
  [:map
   [:job :any]])

;; ---------------------------------------------------------------------------
;; Jobs
;; ---------------------------------------------------------------------------

(def JobType
  [:enum {:description "Type of async job"}
   :media/rescan
   :media/retag
   :media/taglines
   :media/recategorize
   :media/retag-episodes
   :media/curate-all
   :media/pseudovision-sync
   :media/sync-from-pseudovision
   :media/tag-audit
   :media/tag-triage
   :media/scheduling-quarterly
   :media/scheduling-monthly
   :generate-bumpers])

;; NOTE: keep JobType in sync with the job types submitted in
;; tunarr.scheduler.http.api.media.

(def JobStatus
  [:enum {:description "Current job status"}
   :queued :running :succeeded :failed])

(def JobProgress
  "Standard progress shape for item-based jobs. Open map: individual jobs may
   report extra keys (e.g. :page, :library)."
  [:map {:closed false}
   [:phase        {:optional true} [:maybe :string]]
   [:total        {:optional true} [:maybe :int]]
   [:completed    {:optional true} [:maybe :int]]
   [:failed       {:optional true} [:maybe :int]]
   [:skipped      {:optional true} [:maybe :int]]
   [:current-item {:optional true} [:maybe [:map {:closed false}
                                            [:id   {:optional true} [:maybe :string]]
                                            [:name {:optional true} [:maybe :string]]]]]])

(def Job
  [:map
   [:id       JobId]
   [:type     JobType]
   [:status   JobStatus]
   [:metadata {:optional true} [:maybe :map]]
   [:progress {:optional true} [:maybe [:or JobProgress number?]]]
   [:duration-ms {:optional true} [:maybe :int]]
   [:error    {:optional true} [:maybe [:map {:closed false}
                                        [:message :string]
                                        [:type :string]]]]
   [:created-at {:optional true} [:maybe :string]]
   [:started-at {:optional true} [:maybe :string]]
   [:completed-at {:optional true} [:maybe :string]]
   ;; Result of the task-fn (only present once the job is :succeeded or :failed).
   ;; Previously this was missing from the schema, so /api/jobs/:id always
   ;; stripped `:result` even though the runner stored it. That hid the
   ;; :synced/:skipped/:errors counts from sync-from-pseudovision and masked
   ;; the silent CHECK-constraint failures uncovered in 2026-07-18.
   ;;
   ;; FOLLOWUP BUG (live-tested 2026-07-19): I originally wrote this as
   ;; `[:maybe :map]`, but in Malli that is closed-empty — it permits the
   ;; empty map and strips every other key. Live response confirmed
   ;; `:result: {}` for jobs whose runner actually stored
   ;; `{:synced … :skipped … :errors …}`. The closed-false shape mirrors
   ;; every other `[:maybe [:map {:closed false}]]` in this file.
   [:result   {:optional true} [:maybe [:map {:closed false}]]]])

(def JobSubmitResponse
  [:map
   [:job Job]])

(def JobListResponse
  [:map
   [:jobs [:vector Job]]])

(def JobInfoResponse
  [:map
   [:job Job]])

;; ---------------------------------------------------------------------------
;; Media operations
;; ---------------------------------------------------------------------------

(def ProcessName
  [:keyword {:description "Process name (e.g. :process/tagging, :process/categorization)"}])

(def ProcessTimestamp
  [:string {:description "ISO-8601 timestamp of when process last ran"}])

(def ProcessTimestamps
  [:map-of {:description "Map of process names to their last run timestamps"}
   ProcessName ProcessTimestamp])

(def MediaId
  [:string {:min 1 :description "Media item identifier (catalog ID or external/Jellyfin ID)"}])

(def MediaType
  [:enum {:description "Type of media item"}
   :movie :series :episode :season])

(def MediaMetadata
  [:map {:closed false}
   [:tunarr.scheduler.media/id {:optional true} :string]
   [:tunarr.scheduler.media/name {:optional true} :string]
   [:tunarr.scheduler.media/type {:optional true} MediaType]
   [:tunarr.scheduler.media/process-timestamps {:optional true} :any]])

(def Library
  [:map
   [:id   {:optional true} [:maybe :int]]
   [:kind LibraryName]
   [:name :string]])

(def LibraryListResponse
  [:map
   [:libraries [:vector Library]]])

(def MediaListResponse
  [:map
   [:media [:vector MediaMetadata]]])

(def MediaItemResponse
  MediaMetadata)

(def MigrateToPseudovisionRequest
  [:map
   [:dry-run            {:optional true} :boolean]
   [:include-categories {:optional true} :boolean]
   [:batch-size         {:optional true} [:int {:min 1 :max 1000}]]
   [:delay-ms           {:optional true} [:int {:min 0}]]])

(def MigrationResponse
  [:map
   [:message          :string]
   [:items-processed  {:optional true} :int]
   [:items-migrated   {:optional true} :int]
   [:errors           {:optional true} [:vector :string]]])

;; SyncFromPseudovisionResponse removed: /api/media/:library/sync-from-pseudovision
;; now runs async via the job runner and returns JobSubmitResponse (202).

(def MigrateCatalogIdsResponse
  [:map
   [:message  :string]
   [:migrated {:optional true} :int]])

;; ---------------------------------------------------------------------------
;; Channel operations
;; ---------------------------------------------------------------------------

(def ChannelSyncResponse
  [:map
   [:created   {:optional true} :int]
   [:updated   {:optional true} :int]
   [:unchanged {:optional true} :int]
   [:pending   {:optional true} :int]
   [:errors    {:optional true} :int]
   [:details   {:optional true} [:vector [:map {:closed false}]]]])

(def ChannelScheduleInfoResponse
  "Current schedule state for a channel."
  [:map
   [:channel-id    ChannelId]
   [:channel-name  :string]
   [:schedule-id   {:optional true} [:maybe :int]]
   [:schedule      {:optional true} [:maybe [:map {:closed false}]]]
   [:slots         {:optional true} [:maybe [:vector [:map {:closed false}]]]]
   [:upcoming-events {:optional true} [:maybe [:vector [:map {:closed false}]]]]])

;; ---------------------------------------------------------------------------
;; Browse / catalog exploration
;; ---------------------------------------------------------------------------

(def TagName
  [:string {:min 1 :description "Tag name"}])

(def GenreName
  [:string {:min 1 :description "Genre name"}])

(def ChannelName
  [:string {:min 1 :description "Channel name"}])

(def TagSample
  [:map
   [:tag          :string]
   [:usage-count  {:optional true} [:maybe :int]]
   [:example-titles {:optional true} [:maybe [:vector :string]]]])

(def TagListResponse
  [:map
   [:tags [:vector TagSample]]])

(def GenreListResponse
  [:map
   [:genres [:vector :string]]])

(def ChannelInfo
  [:map
   [:name        :string]
   [:full-name   {:optional true} [:maybe :string]]
   [:id          {:optional true} [:maybe :string]]
   [:description {:optional true} [:maybe :string]]])

(def ChannelListResponse
  [:map
   [:channels [:vector ChannelInfo]]])

;; ---------------------------------------------------------------------------
;; Dimensions
;; ---------------------------------------------------------------------------

(def DimensionName
  [:string {:min 1 :description "Dimension name (e.g. 'channel', 'genre', 'age-suitability')"}])

(def DimensionInfo
  [:map
   [:name :string]
   [:value-count {:optional true} [:maybe :int]]])

(def DimensionValueInfo
  [:map
   [:value :string]
   [:usage-count {:optional true} [:maybe :int]]])

;; Channel descriptions live alongside the values list but in a separate
;; payload so the simple `/values` endpoint stays cheap to call (it is
;; hit on every browse page load). The richer `/descriptions` endpoint
;; is what Grout fetches once at startup to seed its Tunabrain prompt
;; with per-channel context — without it the LLM has to guess what
;; `toontown` means and often invents a hallucinated value
;; (e.g. `educational`) that the controlled-vocabulary guard then
;; drops, leaving rows with `channel: null`.
(def DimensionDescriptionInfo
  [:map
   [:value :string]
   [:description :string]])

(def DimensionListResponse
  [:map
   [:dimensions [:vector DimensionInfo]]])

(def DimensionValueListResponse
  [:map
   [:values [:vector DimensionValueInfo]]])

(def DimensionDescriptionListResponse
  [:map
   [:values [:vector DimensionDescriptionInfo]]])

(def MediaCategoriesResponse
  [:map
   [:categories [:map-of :string [:vector :string]]]])

;; ---------------------------------------------------------------------------
;; Strategy
;; ---------------------------------------------------------------------------

(def StrategyId
  [:string {:min 1 :description "Strategy UUID"}])

(def StrategyPeriod
  [:enum {:description "Strategy period"} :monthly :quarterly])

(def StrategyStatus
  [:enum {:description "Strategy lifecycle status"} :draft :applied :rejected :reverted])

(def ChannelAdjustment
  [:map
   [:channel :string]
   [:theme :string]
   [:notes {:optional true} [:maybe :string]]])

(def SpecialEvent
  [:map
   [:name :string]
   [:date :string]
   [:description {:optional true} [:maybe :string]]])

(def Strategy
  [:map
   [:id StrategyId]
   [:period StrategyPeriod]
   [:created-at :string]
   [:status StrategyStatus]
   [:strategy :string]
   [:channel-adjustments [:vector ChannelAdjustment]]
   [:special-events [:vector SpecialEvent]]
   [:channels [:vector :string]]
   [:applied-at {:optional true} [:maybe :string]]
   [:raw {:optional true} [:maybe [:map {:closed false}]]]
   [:error {:optional true} [:maybe :string]]])

(def StrategyListResponse
  [:map
   [:strategies [:vector Strategy]]])

(def GenerateStrategyRequest
  [:map
   [:period {:optional true} StrategyPeriod]])

(def StrategyListQuery
  "Optional filters for the strategy list endpoint."
  [:map
   [:period {:optional true} StrategyPeriod]
   [:status {:optional true} StrategyStatus]])

(def CurrentStrategyQuery
  "Optional period selector for the current-strategy endpoint."
  [:map
   [:period {:optional true} StrategyPeriod]])

;; ---------------------------------------------------------------------------
;; Layered-grid plans (UI read access + operator guidance)
;; ---------------------------------------------------------------------------
;;
;; Response bodies carry the stored wire-contract artifacts (Grid, Override[],
;; DailySlot[], FeasibilityReport) verbatim, so the response schemas are open
;; maps that assert only the envelope keys — the nested shapes are validated at
;; the storage boundary against tunarr.scheduler.scheduling.contracts.

(def PlannedChannelsResponse
  [:map [:channels [:vector :string]]])

(def GridRecord
  "A stored, frozen grid version plus its feasibility snapshot. `:channel`
   is the canonical TS `channel.id` UUID (stable across channel renames);
   `:channel-name` is the human-readable display name from the config,
   included for UI convenience."
  [:map {:closed false}
   [:id :string]
   [:channel :string]
   [:channel-name {:optional true} [:maybe :string]]
   [:quarter :string]
   [:year :int]
   [:version :int]
   [:status :string]
   [:grid :any]
   [:created-at {:optional true} [:maybe :string]]
   [:grid_id {:optional true} [:maybe :string]]
   [:feasibility {:optional true} :any]])
(def GridListResponse
  [:map [:grids [:vector GridRecord]]])

(def OverridesRecord
  [:map {:closed false}
   [:id :string]
   [:channel :string]
   [:month :string]
   [:version :int]
   [:status :string]
   [:overrides [:vector :any]]])

(def OverridesListResponse
  [:map [:overrides [:vector OverridesRecord]]])

(def SchedulePreviewResponse
  [:map {:closed false}
   [:channel :string]
   [:start :string]
   [:end :string]
   [:grid_id {:optional true} [:maybe :string]]
   [:slots [:vector :any]]])

(def ChannelPlanResponse
  "Combined dashboard view for one channel."
  [:map {:closed false}
   [:channel :string]
   [:quarter :string]
   [:year :int]
   [:month :string]
   [:grid {:optional true} [:maybe :any]]
   [:overrides {:optional true} [:maybe :any]]
   [:guidance {:optional true} [:maybe :any]]])

(def ChannelGuidance
  "Per-channel operator guidance — the manual-input surface fed into generation."
  [:map {:closed false}
   [:channel {:optional true} :string]
   [:strategic_guidance {:optional true} [:maybe :string]]
   [:quarterly_theme {:optional true} [:maybe :string]]
   [:monthly_theme {:optional true} [:maybe :string]]
   [:planned_events {:optional true} [:vector :string]]
   [:updated-at {:optional true} :string]])

(def ChannelGuidanceUpdate
  "Editable guidance fields (PUT body). Absent fields are left unchanged."
  [:map
   [:strategic_guidance {:optional true} [:maybe :string]]
   [:quarterly_theme {:optional true} [:maybe :string]]
   [:monthly_theme {:optional true} [:maybe :string]]
   [:planned_events {:optional true} [:vector :string]]])

(def GridQuery
  [:map
   [:quarter {:optional true} [:string {:description "Quarter label, e.g. 'Q1'"}]]
   [:year {:optional true} [:int {:description "Calendar year, e.g. 2026"}]]])

(def OverridesQuery
  [:map
   [:month {:optional true} [:string {:description "Month key, 'YYYY-MM'"}]]])

(def PreviewQuery
  [:map
   [:start {:optional true} [:string {:description "Inclusive start date, 'YYYY-MM-DD'"}]]
   [:end {:optional true} [:string {:description "Exclusive end date, 'YYYY-MM-DD'"}]]])

;; ---------------------------------------------------------------------------
;; Periodic scheduling tasks (triggered by k8s CronJobs)
;; ---------------------------------------------------------------------------

(def SchedulingTaskResponse
  "Open envelope for a scheduling-task result. The concrete payload varies by
   task (daily/weekly return per-channel :results; monthly/quarterly return the
   generated :strategy), so only :task is required."
  [:map {:closed false}
   [:task :string]])

(def SchedulingJobResponse
  "Response for async scheduling tasks (monthly/quarterly) that return
   immediately with a job ID for polling."
  [:map {:closed false}
   [:task :string]
   [:job Job]])

(def ChannelSelector
  "A single channel selector value, or a repeatable list of them."
  [:or [:string {:min 1}] [:vector [:string {:min 1}]]])

(def ChannelFilter
  "Optional repeatable selectors to limit scheduling to specific channels:
   ?channel=key (by config key name) and/or ?channel_id=uuid (by channel id)."
  [:map
   [:channel    {:optional true} ChannelSelector]
   [:channel_id {:optional true} ChannelSelector]])

(def DailyTaskQuery
  [:map
   [:horizon    {:optional true} [:int {:min 1 :max 365 :description "Days to schedule ahead"}]]
   [:channel    {:optional true} ChannelSelector]
   [:channel_id {:optional true} ChannelSelector]])

(def DateTaskQuery
  "ChannelFilter plus an optional ?date selecting the target period. For
   `quarterly` the grid's quarter/year come from that date; for `monthly` the
   month comes from it. Default today. Pass a date inside the upcoming
   period (run a week or so before the boundary) to pre-generate ahead of time."
  [:map
   [:date       {:optional true} [:string {:description "Target date 'YYYY-MM-DD'; the target quarter/month is derived from it. Default today."}]]
   [:channel    {:optional true} ChannelSelector]
   [:channel_id {:optional true} ChannelSelector]])

;; Backwards-compatible alias — the quarterly route referenced this name.
(def QuarterlyTaskQuery DateTaskQuery)

;; ---------------------------------------------------------------------------
;; Query parameters
;; ---------------------------------------------------------------------------

(def ProcessActionName
  [:enum {:description "Process name for timestamp reset"}
   "retag" "recategorize" "episode-tagging"])

(def ProcessResetResponse
  [:map
   [:process :string]
   [:reset :boolean]
   [:media-id {:optional true} [:maybe :string]]
   [:library {:optional true} [:maybe :string]]])

(def MediaActionResponse
  [:map
   [:media-id :string]
   [:action :string]
   [:submitted {:optional true} [:maybe :boolean]]
   [:synced {:optional true} [:maybe :boolean]]
   [:tags {:optional true} [:maybe [:vector :string]]]
   [:error {:optional true} [:maybe :string]]])

;; ---------------------------------------------------------------------------
;; Per-item tag & dimension editing (manual curation, e.g. from Marquee)
;; ---------------------------------------------------------------------------

(def MediaTagsRequest
  "Body for adding/replacing tags on a single media item."
  [:map
   [:tags [:vector [:string {:min 1}]]]])

(def MediaTagsResponse
  "Current tags on a media item after a mutation."
  [:map
   [:media-id :string]
   [:tags [:vector :string]]])

(def MediaCategoryValuesRequest
  "Body for adding/replacing values on a dimension for a single media item.
   The optional :rationale is stored alongside each added value."
  [:map
   [:values [:vector [:string {:min 1}]]]
   [:rationale {:optional true} [:maybe :string]]])

(def MediaCategoryValuesResponse
  "Current values for one dimension on a media item after a mutation."
  [:map
   [:media-id :string]
   [:category :string]
   [:values [:vector :string]]])

;; ---------------------------------------------------------------------------
;; Per-item grounding context (Tunabrain tagging/categorization grounding)
;; ---------------------------------------------------------------------------

(def MediaContext
  "The grounding context stored for a media item. Mirrors Tunabrain's
   MediaContext wire object plus two locally-managed fields:
   `operator-edited` (a human correction is sticky and not auto-overwritten)
   and `updated-at`. `source` is Tunabrain-provided provenance, one of
   provided-summary/provided-text/provided-link/wikipedia/none (kept as a
   permissive string)."
  [:map {:closed false}
   [:text            {:optional true} [:maybe :string]]
   [:links           {:optional true} [:vector :string]]
   [:summary         {:optional true} [:maybe :string]]
   [:source          {:optional true} [:maybe :string]]
   [:operator-edited {:optional true} [:maybe :boolean]]
   [:updated-at      {:optional true} [:maybe :string]]])

(def MediaContextResponse
  "The stored context for a media item (nil when none has been captured yet)."
  [:map
   [:media-id :string]
   [:context [:maybe MediaContext]]])

(def MediaContextRequest
  "PUT body to set/replace the stored context on a media item. Sending this
   marks the context operator-edited so a subsequent automatic re-tag will not
   clobber it. Absent fields are treated as empty/null."
  [:map
   [:text    {:optional true} [:maybe :string]]
   [:links   {:optional true} [:vector :string]]
   [:summary {:optional true} [:maybe :string]]])

(def MediaContextLinkRequest
  "Body carrying a single reference URL to add to or remove from the stored
   context's link list."
  [:map
   [:link [:string {:min 1}]]])

(def MediaContextTextRequest
  "Body carrying the free-form operator text note to store on the context."
  [:map
   [:text [:string {:min 1}]]])

(def MediaContextSummaryRequest
  "Body carrying the resolved grounding summary to pin on the context. A
   non-blank summary takes precedence over links/text on the next re-tag."
  [:map
   [:summary [:string {:min 1}]]])

(def ForceQuery
  [:map
   [:force {:optional true} [:enum "true" "false"]]])

(def MediaListQuery
  [:map
   [:kind {:optional true} :string]
   [:q    {:optional true} :string]])

(def TagAuditQuery
  [:map
   [:dry-run {:optional true} [:enum "true" "false"]]])

(def TagTriageQuery
  [:map
   [:dry-run      {:optional true} [:enum "true" "false"]]
   [:target-limit {:optional true} [:int {:min 1 :description "Approximate number of tags the triage should aim to keep"}]]])

(def DimensionCleanupQuery
  [:map
   [:dry-run {:optional true} [:enum "true" "false"]]])
