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

(def SelectionStrategy
  "How Pseudovision picks concrete content within a slot. Mirrors grid.py's
   SelectionStrategy literal and DailySlot.media_selection_strategy."
  [:enum "random" "sequential" "specific"])

;; Optionality below mirrors the Pydantic models in tunabrain
;; src/tunabrain/scheduling/grid.py: a field is `{:optional true}` where the
;; Pydantic field has a default, and `[:maybe …]` where its type is `X | None`.
;; Required (Pydantic `...`) fields stay mandatory.

;; ---------------------------------------------------------------------------
;; Content — the "what plays" payload shared by strips and overrides
;; ---------------------------------------------------------------------------

(def Content
  "media_id conventions: `series:<id>`, `movie:<id>`, `random:<category>`.
   Only `media_id` is required; the rest carry Pydantic defaults."
  [:map
   [:media_id :string]
   [:strategy {:optional true} SelectionStrategy]
   [:marathon {:optional true} :boolean]
   [:category_filters {:optional true} [:vector :string]]
   [:label {:optional true} [:maybe :string]]
   [:notes {:optional true} [:vector :string]]])

;; ---------------------------------------------------------------------------
;; CatalogProfile — the summarized shape of a channel's library (§2.1)
;; ---------------------------------------------------------------------------

(def ShowProfile
  [:map
   [:media_id :string]
   [:title :string]
   [:genres {:optional true} [:vector :string]]
   [:episode_count [:int {:min 0}]]
   [:available_episode_count [:int {:min 0}]]
   [:avg_runtime_minutes {:optional true} [:maybe number?]]
   [:tags {:optional true} [:vector :string]]])

(def GenreProfile
  [:map
   [:genre :string]
   [:show_count [:int {:min 0}]]
   [:episode_count [:int {:min 0}]]])

(def RuntimeBucket
  [:map
   [:label :string]
   [:min_minutes [:int {:min 0}]]
   ;; nullable: the open-ended top bucket (e.g. "120min+") has no max.
   [:max_minutes [:maybe [:int {:min 0}]]]
   [:item_count [:int {:min 0}]]])

(def CatalogProfile
  [:map
   [:channel_scope {:optional true} [:maybe :string]]
   [:total_items [:int {:min 0}]]
   [:total_episodes [:int {:min 0}]]
   [:movie_count {:optional true} [:int {:min 0}]]
   [:shows {:optional true} [:vector ShowProfile]]
   [:genres {:optional true} [:vector GenreProfile]]
   [:runtime_histogram {:optional true} [:vector RuntimeBucket]]
   [:generated_at {:optional true} [:maybe IsoDateTime]]])

;; ---------------------------------------------------------------------------
;; Grid — the frozen weekly skeleton + recurring strips (§2.2)
;; ---------------------------------------------------------------------------

(def DaypartBlock
  [:map
   [:name :string]
   [:start ClockTime]
   [:end ClockTime]
   [:role :string]
   [:genre_focus {:optional true} [:vector :string]]
   [:rationale {:optional true} [:maybe :string]]])

(def DaypartSkeleton
  [:map
   [:channel :string]
   [:blocks {:optional true} [:vector DaypartBlock]]])

(def GridStrip
  [:map
   [:strip_id :string]
   [:days DaysPattern]
   [:start ClockTime]
   [:end ClockTime]
   [:content Content]
   [:priority {:optional true} :int]
   [:daypart {:optional true} [:maybe :string]]])

(def Grid
  [:map
   [:channel :string]
   [:broadcast_day_start {:optional true} ClockTime]
   [:skeleton {:optional true} [:maybe DaypartSkeleton]]
   [:strips {:optional true} [:vector GridStrip]]
   [:default_content {:optional true} [:maybe Content]]])

;; ---------------------------------------------------------------------------
;; Override — sparse monthly deltas (§2.3)
;; ---------------------------------------------------------------------------

(def OverrideScope
  "Exactly one of: a single date, or a recurring day-pattern. The recurring
   window bounds are optional (unbounded when omitted); the maps are closed so
   the two shapes stay mutually exclusive (mirrors grid.py's validator)."
  [:or
   [:map {:closed true}
    [:date IsoDate]]
   [:map {:closed true}
    [:days DaysPattern]
    [:effective_start {:optional true} IsoDate]
    [:effective_end {:optional true} IsoDate]]])

(def ScheduleOverride
  "An Override in the spec; named ScheduleOverride here to avoid colliding with
   the auto-imported java.lang.Override."
  [:map
   [:override_id :string]
   [:scope OverrideScope]
   [:start ClockTime]
   [:end ClockTime]
   [:content Content]
   [:mode {:optional true} [:enum "replace"]]
   [:priority {:optional true} :int]
   [:note {:optional true} [:maybe :string]]])

;; ---------------------------------------------------------------------------
;; ContentPolicy — deterministic per-channel placement constraints
;; ---------------------------------------------------------------------------
;;
;; Unlike operator *guidance* (free-text hints fed to the LLM, which steer but
;; never gate), a ContentPolicy is a HARD constraint the deterministic layer
;; enforces: the feasibility checker blocks a grid that violates it (driving the
;; repair loop) and the publish step substitutes default content at air time.
;; It is a Tunarr-Scheduler-local contract (not part of the Tunabrain wire
;; spec), but it is passed to Tunabrain's propose calls as a best-effort hint so
;; violations are avoided up front rather than only repaired after the fact.

(def Watershed
  "A time-of-day restriction on content carrying a dimension value. Content
   tagged `<dimension>:<value>` (e.g. audience:adult) may air ONLY within the
   half-open window [allowed_from, allowed_to). `allowed_to <= allowed_from`
   means the window crosses midnight — e.g. allowed_from \"22:00\" /
   allowed_to \"06:00\" permits 22:00→06:00 and forbids the daytime in between
   (\"adult content only after 10 PM\")."
  [:map
   [:dimension :string]
   [:value :string]
   [:allowed_from ClockTime]
   [:allowed_to ClockTime]
   [:label {:optional true} [:maybe :string]]])

(def ContentPolicy
  "The per-channel set of hard placement constraints. Currently just watersheds;
   the map is left open so further constraint kinds can be added without a
   contract break."
  [:map {:closed false}
   [:watersheds {:optional true} [:vector Watershed]]])

;; ---------------------------------------------------------------------------
;; FeasibilityReport — repair feedback to Tunabrain (§2.4)
;; ---------------------------------------------------------------------------

(def StripFeasibilityStatus
  [:enum "ok" "tight" "shortfall"])

(def StripFeasibility
  [:map
   [:rule_id :string]
   [:media_id :string]
   [:slots_required [:int {:min 0}]]
   [:episodes_available [:int {:min 0}]]
   [:headroom_ratio {:optional true} [:maybe number?]]
   [:status StripFeasibilityStatus]
   [:message {:optional true} :string]])

(def FeasibilityStatus
  [:enum "ok" "warnings" "blocked"])

(def FeasibilityReport
  [:map
   [:horizon_start IsoDate]
   [:horizon_end IsoDate]
   [:overall_status FeasibilityStatus]
   [:strip_findings {:optional true} [:vector StripFeasibility]]
   [:overlaps {:optional true} [:vector :string]]
   [:uncovered_intervals {:optional true} [:vector :string]]
   ;; Local extension (not in the Tunabrain wire spec): human-readable
   ;; content-policy breaches (e.g. a watershed placing adult content in the
   ;; daytime). Any entry forces `overall_status` to "blocked" so the repair
   ;; loop re-places the offending strip. Also mirrored into `:notes` so a
   ;; Tunabrain repair endpoint that ignores this field still sees the reason.
   [:watershed_violations {:optional true} [:vector :string]]
   [:notes {:optional true} [:vector :string]]])

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
  {:SelectionStrategy SelectionStrategy
   :Content           Content
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
   :Watershed         Watershed
   :ContentPolicy     ContentPolicy
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
