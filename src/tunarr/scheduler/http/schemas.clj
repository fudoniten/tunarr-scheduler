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
   :media/pseudovision-sync])

(def JobStatus
  [:enum {:description "Current job status"}
   :queued :running :succeeded :failed])

(def Job
  [:map
   [:id       JobId]
   [:type     JobType]
   [:status   JobStatus]
   [:metadata {:optional true} [:maybe :map]]
   [:progress {:optional true} [:maybe :int]]
   [:error    {:optional true} [:maybe :string]]
   [:created-at {:optional true} [:maybe :string]]
   [:started-at {:optional true} [:maybe :string]]
   [:finished-at {:optional true} [:maybe :string]]])

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
  [:string {:min 1 :description "Media item identifier"}])

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

(def TagAuditResponse
  [:map
   [:tags-audited :int]
   [:tags-removed :int]
   [:removed      [:vector [:map
                            [:tag    :string]
                            [:reason :string]]]]])

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

;; ---------------------------------------------------------------------------
;; Query parameters
;; ---------------------------------------------------------------------------

(def ForceQuery
  [:map
   [:force {:optional true} [:enum "true" "false"]]])
