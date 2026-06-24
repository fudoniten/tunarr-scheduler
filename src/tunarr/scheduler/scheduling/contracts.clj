(ns tunarr.scheduler.scheduling.contracts
  "Malli schemas mirroring the layered-grid scheduling contracts shared with
   Tunabrain.

   The authoritative source is the tunabrain repo:
     • src/tunabrain/scheduling/grid.py   (Pydantic models)
     • docs/scheduling-grid-spec.md        (prose spec)
   These schemas MUST keep JSON field names identical to those models, because
   they are the wire format exchanged with Tunabrain over HTTP.

   Key convention: the wire format is JSON with snake_case keys. Cheshire parses
   JSON object keys to keywords (`:grid_id`, `:available_episode_count`, …) and
   regenerates them verbatim, so these schemas use snake_case *keyword* keys and
   round-trip without any field renaming — matching the existing payloads in
   `tunarr.scheduler.tunabrain`.

   Schemas are plain Malli vectors (same style as `tunarr.scheduler.http.schemas`)
   so they can also be used for reitit coercion / OpenAPI where these contracts
   appear in request or response bodies."
  (:require [malli.core  :as m]
            [malli.error :as me]))

;; ---------------------------------------------------------------------------
;; Primitives
;; ---------------------------------------------------------------------------

(def ClockTime
  "24-hour wall-clock time, \"HH:MM\". `end <= start` on a strip/override means
   the interval crosses midnight."
  [:re {:description "24h time, HH:MM"} #"^([01]\d|2[0-3]):[0-5]\d$"])

(def IsoDate
  "Calendar date, \"YYYY-MM-DD\"."
  [:re {:description "ISO date, YYYY-MM-DD"} #"^\d{4}-\d{2}-\d{2}$"])

(def IsoDateTime
  "Naive local datetime, \"YYYY-MM-DDTHH:MM:SS\" (the DailySlot / generated_at
   shape). Fractional seconds and an optional offset are tolerated so upstream
   variations still validate."
  [:re {:description "ISO local datetime, YYYY-MM-DDTHH:MM:SS"}
   #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?([+-]\d{2}:\d{2}|Z)?$"])

(def DayName
  [:enum "mon" "tue" "wed" "thu" "fri" "sat" "sun"])

(def DaysPattern
  "Which calendar days a rule applies to: a named group or an explicit list."
  [:or
   [:enum "daily" "weekdays" "weekends"]
   [:vector DayName]])

;; ---------------------------------------------------------------------------
;; Content — the "what plays" payload shared by strips and overrides
;; ---------------------------------------------------------------------------

(def Content
  "media_id conventions: `series:<id>`, `movie:<id>`, `random:<category>`.
   `strategy` is the media-selection strategy resolved by Pseudovision at air
   time (e.g. \"sequential\", \"random\")."
  [:map
   [:media_id :string]
   [:strategy :string]
   [:marathon :boolean]
   [:category_filters [:vector :string]]
   [:label [:maybe :string]]
   [:notes [:vector :string]]])

;; ---------------------------------------------------------------------------
;; CatalogProfile — the summarized shape of a channel's library (§2.1)
;; ---------------------------------------------------------------------------

(def ShowProfile
  [:map
   [:media_id :string]
   [:title :string]
   [:genres [:vector :string]]
   [:episode_count :int]
   [:available_episode_count :int]
   [:avg_runtime_minutes number?]
   [:tags [:vector :string]]])

(def GenreProfile
  [:map
   [:genre :string]
   [:show_count :int]
   [:episode_count :int]])

(def RuntimeBucket
  [:map
   [:label :string]
   [:min_minutes number?]
   [:max_minutes number?]
   [:item_count :int]])

(def CatalogProfile
  [:map
   [:channel_scope :string]
   [:total_items :int]
   [:total_episodes :int]
   [:movie_count :int]
   [:shows [:vector ShowProfile]]
   [:genres [:vector GenreProfile]]
   [:runtime_histogram [:vector RuntimeBucket]]
   [:generated_at IsoDateTime]])

;; ---------------------------------------------------------------------------
;; Grid — the frozen weekly skeleton + recurring strips (§2.2)
;; ---------------------------------------------------------------------------

(def DaypartBlock
  [:map
   [:name :string]
   [:start ClockTime]
   [:end ClockTime]
   [:role :string]
   [:genre_focus [:vector :string]]
   [:rationale :string]])

(def DaypartSkeleton
  [:map
   [:channel :string]
   [:blocks [:vector DaypartBlock]]])

(def GridStrip
  [:map
   [:strip_id :string]
   [:days DaysPattern]
   [:start ClockTime]
   [:end ClockTime]
   [:content Content]
   [:priority :int]
   [:daypart :string]])

(def Grid
  [:map
   [:channel :string]
   [:broadcast_day_start ClockTime]
   [:skeleton DaypartSkeleton]
   [:strips [:vector GridStrip]]
   [:default_content [:maybe Content]]])

;; ---------------------------------------------------------------------------
;; Override — sparse monthly deltas (§2.3)
;; ---------------------------------------------------------------------------

(def OverrideScope
  "Exactly one of: a single date, or a recurring day-pattern bounded to a window.
   The maps are closed so the two shapes stay mutually exclusive."
  [:or
   [:map {:closed true}
    [:date IsoDate]]
   [:map {:closed true}
    [:days DaysPattern]
    [:effective_start IsoDate]
    [:effective_end IsoDate]]])

(def ScheduleOverride
  "An Override in the spec; named ScheduleOverride here to avoid colliding with
   the auto-imported java.lang.Override."
  [:map
   [:override_id :string]
   [:scope OverrideScope]
   [:start ClockTime]
   [:end ClockTime]
   [:content Content]
   [:mode :string]
   [:priority :int]
   [:note [:maybe :string]]])

;; ---------------------------------------------------------------------------
;; FeasibilityReport — repair feedback to Tunabrain (§2.4)
;; ---------------------------------------------------------------------------

(def StripFeasibilityStatus
  [:enum "ok" "tight" "shortfall"])

(def StripFeasibility
  [:map
   [:rule_id :string]
   [:media_id :string]
   [:slots_required :int]
   [:episodes_available :int]
   [:headroom_ratio number?]
   [:status StripFeasibilityStatus]
   [:message :string]])

(def FeasibilityStatus
  [:enum "ok" "warnings" "blocked"])

(def FeasibilityReport
  [:map
   [:horizon_start IsoDate]
   [:horizon_end IsoDate]
   [:overall_status FeasibilityStatus]
   [:strip_findings [:vector StripFeasibility]]
   [:overlaps [:vector :string]]
   [:uncovered_intervals [:vector :string]]
   [:notes [:vector :string]]])

;; ---------------------------------------------------------------------------
;; DailySlot — the expander's concrete dated output (§2.5)
;; ---------------------------------------------------------------------------

(def DailySlot
  [:map
   [:start_time IsoDateTime]
   [:end_time IsoDateTime]
   [:media_id :string]
   [:media_selection_strategy :string]
   [:category_filters [:vector :string]]
   [:notes [:vector :string]]])

;; ---------------------------------------------------------------------------
;; Registry + validation helpers
;; ---------------------------------------------------------------------------

(def registry
  "Convenience map of contract-name → schema, for tests and lookups."
  {:Content           Content
   :ShowProfile       ShowProfile
   :GenreProfile      GenreProfile
   :RuntimeBucket     RuntimeBucket
   :CatalogProfile    CatalogProfile
   :DaypartBlock      DaypartBlock
   :DaypartSkeleton   DaypartSkeleton
   :GridStrip         GridStrip
   :Grid              Grid
   :OverrideScope     OverrideScope
   :Override          ScheduleOverride
   :StripFeasibility  StripFeasibility
   :FeasibilityReport FeasibilityReport
   :DailySlot         DailySlot})

(defn valid?
  "True when `value` conforms to `schema`."
  [schema value]
  (m/validate schema value))

(defn explain
  "Raw Malli explanation map for a non-conforming value (nil when valid)."
  [schema value]
  (m/explain schema value))

(defn humanize
  "Human-readable explanation for a non-conforming value (nil when valid)."
  [schema value]
  (some-> (m/explain schema value) me/humanize))
