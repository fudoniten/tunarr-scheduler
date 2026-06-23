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
   :media/pseudovision-sync
   :media/tag-audit
   :media/tag-triage])

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
   [:completed-at {:optional true} [:maybe :string]]])

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

(def SyncFromPseudovisionResponse
  [:map
   [:message :string]
   [:synced  {:optional true} :int]
   [:created {:optional true} :int]
   [:updated {:optional true} :int]])

(def MigrateCatalogIdsResponse
  [:map
   [:message  :string]
   [:migrated {:optional true} :int]])

;; ---------------------------------------------------------------------------
;; Channel operations
;; ---------------------------------------------------------------------------

(def ChannelSyncResponse
  [:map
   [:channels-synced {:optional true} :int]
   [:channels-created {:optional true} :int]
   [:channels-updated {:optional true} :int]
   [:message         :string]])

(def ChannelScheduleRequest
  [:map
   [:horizon {:optional true} [:int {:min 1 :max 365 :description "Days to schedule ahead"}]]])

(def ChannelScheduleResponse
  [:map
   [:message        :string]
   [:events-created {:optional true} :int]])

(def ChannelScheduleInfoResponse
  "Current schedule state for a channel."
  [:map
   [:channel-id    ChannelId]
   [:channel-name  :string]
   [:schedule-id   {:optional true} [:maybe :int]]
   [:schedule      {:optional true} [:maybe [:map {:closed false}]]]
   [:slots         {:optional true} [:maybe [:vector [:map {:closed false}]]]]
   [:upcoming-events {:optional true} [:maybe [:vector [:map {:closed false}]]]]])

(def ApplyTemplateRequest
  "Body for applying a schedule template to a channel.
   Provide either :template (full map) or :template-key (keyword)."
  [:map
   [:horizon      {:optional true} [:int {:min 1 :max 365}]]
   [:template-key {:optional true} :keyword]
   [:template     {:optional true}
    [:map
     [:name :string]
     [:slots [:vector [:map
                       [:time {:optional true} :string]
                       [:fill-mode {:optional true} [:enum :once :count :block :flood]]
                       [:block-duration {:optional true} :string]
                       [:duration-hours {:optional true} :int]
                       [:item-count {:optional true} :int]
                       [:required-tags {:optional true} [:vector :keyword]]
                       [:excluded-tags {:optional true} [:vector :keyword]]
                       [:playback-order {:optional true} [:enum :chronological :random :shuffle :semi-sequential :season-episode]]
                       [:batch-size {:optional true} :int]
                       [:days-of-week {:optional true} [:set [:enum :mon :tue :wed :thu :fri :sat :sun]]]]]]]]])

(def ApplyAllTemplatesResponse
  "Map of channel-key → apply-template result (or :no-template / :no-channel-id)."
  [:map-of :keyword :any])

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
;; Intent / natural-language scheduling
;; ---------------------------------------------------------------------------

(def IntentRequest
  [:map
   [:instruction :string]
   [:dry-run {:optional true} :boolean]
   [:horizon {:optional true} [:int {:min 1 :max 365}]]])

(def Operation
  [:map {:closed false}
   [:type :string]
   [:slot_index {:optional true} :int]
   [:slot {:optional true} :any]
   [:new_slot {:optional true} :any]
   [:changes {:optional true} :any]
   [:success {:optional true} :boolean]
   [:result {:optional true} :any]
   [:error {:optional true} [:maybe :string]]])

(def Preview
  [:map
   [:affected_blocks [:vector :string]]
   [:description :string]])

(def IntentResponse
  [:map
   [:success :boolean]
   [:reasoning :string]
   [:operations [:vector Operation]]
   [:preview Preview]
   [:applied? {:optional true} :boolean]])

;; ---------------------------------------------------------------------------
;; Strategy
;; ---------------------------------------------------------------------------

(def StrategyId
  [:string {:min 1 :description "Strategy UUID"}])

(def StrategyPeriod
  [:enum {:description "Strategy period"} :monthly :quarterly])

(def StrategyStatus
  [:enum {:description "Strategy lifecycle status"} :draft :applied :rejected])

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

;; ---------------------------------------------------------------------------
;; Query parameters
;; ---------------------------------------------------------------------------

(def ForceQuery
  [:map
   [:force {:optional true} [:enum "true" "false"]]])

(def KindQuery
  [:map
   [:kind {:optional true} :string]])

(def TagAuditQuery
  [:map
   [:dry-run {:optional true} [:enum "true" "false"]]])

(def TagTriageQuery
  [:map
   [:dry-run      {:optional true} [:enum "true" "false"]]
   [:target-limit {:optional true} [:int {:min 1 :description "Approximate number of tags the triage should aim to keep"}]]])
