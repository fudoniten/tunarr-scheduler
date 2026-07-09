# Duration-Aware Scheduling — Phases 2 & 3 Design

**Status:** Proposed — not yet implemented. This is a design doc for review, not
a build log; nothing below should be treated as decided until the open
questions in §5 are answered.

**Builds on:** Phase 1 shipped as
[pseudovision#119](https://github.com/fudoniten/pseudovision/pull/119) —
duration-aware selection at air time. See §1 for the recap.

**Numbering note:** these phase numbers are local to this document (a
duration-aware-scheduling initiative) and are independent of `ROADMAP.md`'s
Phase 0–6 (the layered-grid rollout, already done). Where useful, cross-refs to
that numbering are called out explicitly. Phase 3 (§4) formalizes the
`query_media_count` "Phase 7" idea already flagged in tunabrain's
`docs/scheduling-grid-spec.md` §8 — see §4.1.

---

## 1. Recap: what Phase 1 fixed and what it didn't

**The reported bug:** Tunarr Scheduler generates schedules "blindly" — a strip
says "play a `#random:movie` for 1 hour," a random movie is selected with no
regard for whether it fits, and the resulting event overlaps the next slot.

**Phase 1 (shipped, pseudovision-side only)** fixed the *air-time* half of
this, in `pseudovision/src/pseudovision/http/api/daily_slots.clj`:

- `select-fitting-items` narrows a `random:<category>` pool to items whose
  runtime plausibly fits the slot (within a 15-minute tolerance) before the
  existing rotation logic picks among them.
- `create-event-from-slot` stamps the event's `finish_at` from the picked
  item's **real** duration, not the slot's nominal boundary.
- When an item still overflows its slot (nothing in the pool was a good fit),
  the **next** slot's start shifts forward to begin right after it — no
  overlap, at the cost of an occasional late start. A slot that finishes on
  time settles back to its own nominal time with no accumulated drift.

**What Phase 1 did *not* fix:** grid *authoring* is still blind. Tunabrain's
LLM can still author a 60-minute `random:movie` strip even when the catalog
has zero movies within 15 minutes of that length. Phase 1 absorbs the cost of
that mistake every single night (falling back to "closest available" and
logging a warning) instead of the mistake being caught once, at authoring
time, and not repeated for the whole quarter the grid is frozen for.

Phases 2 and 3 push the fix upstream to where it's cheaper: grid authoring
happens once a quarter; a bad strip currently costs a slightly-wrong airing
*every night* until the next quarterly re-author.

---

## 2. Current histogram: what exists and its limits

`pseudovision/src/pseudovision/db/catalog.clj`'s `list-runtime-histogram`
already buckets the catalog by runtime and this rolls all the way up through
`CatalogProfile.runtime_histogram` into the Tunabrain prompt
(`quarterly_grid.py:82-84`, `summarize_catalog_profile`). Two limits stop it
from doing the job this design needs:

1. **Global only.** One histogram for the whole catalog. There's no way to ask
   "how many *movies* are ~100 minutes," only "how many *items of any kind*
   are 90+ minutes" — and the strip-fill mismatch is specifically about
   `random:<category>` pools (movies vs. sitcoms vs. documentaries all having
   wildly different length distributions).
2. **Coarse open-ended top bucket.** Buckets stop at `60-90min` then jump to an
   open-ended `90+min` (`bucket->min-max`, `catalog.clj:269-281`) — a 91-minute
   film and a 3-hour epic are indistinguishable.
3. **Purely advisory.** It's rendered as one text line
   (`"Runtimes: 20-30min: 450, ..."`) in the LLM prompt and checked by
   *nothing* deterministic. `feasibility.clj` checks episode counts, strip
   overlaps, and broadcast-day coverage — never "does content of this length
   exist for this slot."

---

## 3. Phase 2 — Dimensioned histogram + a feasibility duration check

### 3.1 A note on which "category" dimension to hook into

`DIMENSION_CLEANUP.md` (tunarr-scheduler) documents an active migration away
from hardcoded fields (`genres`, `channels`, `kid_friendly`) toward a unified
tag/dimension model (`genre:action`, `channel:scifi`, ...) living in
`metadata_tags` / exported as `tag_aggregates`. Tellingly,
`tunarr-scheduler/scheduling/feasibility.clj`'s `category-episode-count`
already reads `:tag_aggregates` as primary and falls back to the (deprecated)
`:genres` field only for backward compat — even though `tag_aggregates` isn't
yet declared in `contracts.clj`'s `CatalogProfile` malli schema, a small
existing gap this design should close rather than compound.

**Decision for this design: the new per-category histogram keys off the tag
dimension (`tag_aggregates`-shaped), not `genres`/`GenreProfile`.** This is
also the exact dimension `resolve-by-category` in pseudovision's
`daily_slots.clj` already matches a `random:<category>` slot's category
argument against — so the histogram, the feasibility check, and the air-time
selector all reason about the same space.

### 3.2 Pseudovision: dimensioned histogram

In `pseudovision/db/catalog.clj`:

- **Finer, closed buckets.** Replace the current scheme (`0-10, 10-20, ...,
  60-90, 90+`) with 15-minute buckets from 0 up to ~210 minutes, then a
  `210+min` open bucket. This matches Phase 1's `default-fit-tolerance-minutes`
  (15) granularity, so the two systems reason about duration in the same
  units — a "tight" finding in feasibility and a "no fit within tolerance"
  warning at air time now describe the same bucket boundaries.
- **New tag-scoped query**, analogous to the existing `list-tag-aggregates`:
  a `list-tag-runtime-histogram` grouping by `(tag, bucket)` instead of just
  `bucket`. Bounded in size by `tags × buckets`, not by catalog size — holds
  the existing "sized the same regardless of library size" invariant from
  `CatalogProfile`'s own docstring.
- Exposed from `build-catalog-profile` as a new field alongside (not
  replacing) the existing global `runtime_histogram` — the global histogram
  still answers Pass A's coarse "what's the shape of the whole library"
  question; the per-tag histogram answers Pass B's and feasibility's "does
  *this* category have content at *this* length" question.

### 3.3 Wire contract changes

Mirrored exactly in both repos, per the project's existing convention (malli
in tunarr-scheduler mirrors pydantic in tunabrain field-for-field):

```python
# tunabrain/src/tunabrain/scheduling/grid.py

class TagRuntimeHistogram(_WireModel):
    """Runtime distribution for one tag (e.g. 'genre:movie'), for slot-fit
    reasoning within a specific random:<category> pool."""

    tag: str
    buckets: list[RuntimeBucket] = Field(default_factory=list)


class CatalogProfile(_WireModel):
    ...
    runtime_histogram: list[RuntimeBucket] = Field(default_factory=list)
    tag_runtime_histograms: list[TagRuntimeHistogram] = Field(default_factory=list)
    tag_aggregates: list[TagAggregate] = Field(default_factory=list)  # closes the
    # existing gap noted in §3.1 — feasibility.clj already reads this key
```

```clojure
;; tunarr-scheduler/src/tunarr/scheduler/scheduling/contracts.clj

(def TagAggregate
  [:map
   [:tag :string]
   [:show_count [:int {:min 0}]]
   [:episode_count [:int {:min 0}]]])

(def TagRuntimeHistogram
  [:map
   [:tag :string]
   [:buckets [:vector RuntimeBucket]]])

(def CatalogProfile
  [:map
   ...
   [:tag_aggregates {:optional true} [:vector TagAggregate]]
   [:tag_runtime_histograms {:optional true} [:vector TagRuntimeHistogram]]])
```

Both new fields are additive/optional — old Tunabrain builds simply don't read
`tag_runtime_histograms`; old Tunarr Scheduler builds ignore the finer bucket
scheme wherever it isn't explicitly consumed.

### 3.4 Feasibility: a new duration-fit finding

In `tunarr-scheduler/scheduling/feasibility.clj`, extend the per-strip
`finding` function (it already branches on `media_id` kind) with a duration
check that only applies to `random:<category>` strips (`kind = "random"`):

1. Compute the strip's own wall-clock duration from `start`/`end` (same
   `parse-mins`/interval math the overlap detector already uses).
2. Bucket that duration the same way Pseudovision's histogram does (15-minute
   buckets), and look up `tag_runtime_histograms` for `(media-arg media-id)`.
3. Sum `item_count` across buckets within the existing tolerance window (reuse
   the same ±15-minute logic Phase 1 uses at air time, so "tight" in
   feasibility and "fallback to closest" at air time describe the same
   boundary).
4. Compare that count against `slots_required` (already computed by
   `slots-required` for the episode-count check) using the same
   `margin`/floor-style thresholds already established (`shortfall` if zero
   in-tolerance items exist, `tight` if under a floor relative to
   `slots_required`, else `ok`).

**Design choice: fold into the existing per-strip finding, don't add a new
`StripFeasibility` shape.** A strip can be `ok` on episode-count but flagged
for duration, or vice versa — rather than two independent finding lists (which
would touch `FeasibilityReport`'s schema and every consumer of
`strip_findings`), take the *worse* of the two statuses and concatenate
`message` text. `quarterly_grid.py`'s `build_grid_repair_prompt` already
renders `f.message` verbatim into the repair prompt, so this needs zero
changes there — the LLM just sees a more informative message on the strips
that were already being flagged, or newly starts seeing some strips flagged
for a reason it previously had no way to know about.

### 3.5 Rollout

1. Ship Pseudovision's histogram dimensioning (§3.2) — independently
   deployable, additive to the aggregate response, no consumer required to
   change first.
2. Mirror the wire contracts (§3.3) in both repos.
3. Ship the feasibility duration finding (§3.4) — a pure function, unit-tested
   in isolation with a hand-built `CatalogProfile` fixture exactly like
   `feasibility_test.clj`'s existing style. Immediately starts catching
   mismatches in the *next* quarterly propose→check→repair loop; no changes
   needed to Phase 1's air-time code, which operates one layer down and stays
   as the last-resort safety net.

---

## 4. Phase 3 — Slot-menu authoring

### 4.1 Why feasibility-as-backstop isn't enough

Phase 2 catches a duration mismatch *after* the LLM invents a strip, then
spends a repair round fixing a mistake that shouldn't have been structurally
possible to make. `orchestration.clj`'s repair loop is bounded (default 3
rounds) — every round spent fixing an avoidable duration mistake is a round
not available for a real content or coverage problem.

This is exactly the instinct behind the original ask: **don't let the LLM
invent slot lengths freeform against a shape-only profile; have the
deterministic layer generate the *set* of duration-feasible options, and let
the LLM (good at judgment and coherence) choose and arrange among them (bad at
arithmetic).** This also formalizes the `query_media_count` "Phase 7" idea
already flagged in tunabrain's `docs/scheduling-grid-spec.md` §8 as a pull
tool for "feasibility questions the CatalogProfile did not pre-answer" — a
precomputed menu is a better fit than an ad hoc pull tool here, because a
tool-calling loop would require the LLM to hold state across calls, which
cuts against this design's explicit invariants ("Tunabrain is stateless," "the
LLM never does capacity arithmetic" — `SCHEDULING.md`'s Philosophy section).

*(Aside: tunabrain's repo has an existing LangGraph tool-calling agent —
`agents/scheduling_agent.py` / `agents/scheduling_tools.py` — but it's
vestigial, wired to neither `routes.py` nor `app.py`, left over from the
pre-grid batch design the current architecture superseded. Phase 3 should not
build on it; a precomputed menu needs no agentic loop.)*

### 4.2 New concept: the DaypartCandidate menu

Computed by **Tunarr Scheduler**, not Tunabrain — capacity arithmetic stays
deterministic and out of the LLM's hands, per the existing invariant — from
the dimensioned histogram (§3), for a given daypart's bounds:

```clojure
;; sketch — new ns, e.g. scheduling/candidates.clj

CandidateSlot
  duration_minutes: int    ; e.g. 60
  category: string         ; e.g. "movie" — the tag this slot would draw from
  available_count: int     ; item_count from tag_runtime_histograms, this bucket

DaypartCandidate
  layout_id: string
  slots: CandidateSlot[]   ; tiles the daypart's [start, end) contiguously
  weight: float            ; relative availability — drives sampling frequency
```

A 4-hour prime-time daypart, for example, might offer three candidate
layouts: `[2×2h movie]`, `[4×1h]`, `[2h movie + 4×30min sitcom]` — each
*already* duration-feasible by construction (built from buckets known to have
inventory), rather than generated freely and rejected after the fact.
`weight` is proportional to the pool depth backing each layout's slots, which
is the direct mechanism for "slot frequency correlated with media
availability" — a layout leaning on a bucket with 3 items should be surfaced
rarely; one backed by 200 items, often. (The exact aggregation — minimum
across a layout's slots vs. a product — is an open question, §5.1.)

### 4.3 The architectural fork: how candidates reach Pass B

This is the part of Phase 3 that isn't just "add a field" — it interacts with
how Tunabrain's single HTTP call is structured today.

Currently, `POST /propose-quarterly-grid` (`QuarterlyGridRequest`) is **one
call** that runs *both* passes internally: Pass A (dayparting) proposes the
`DaypartSkeleton`, then a loop over `skeleton.blocks` calls Pass B once per
block (`quarterly_grid.py: propose_quarterly_grid`). Tunarr Scheduler never
sees the daypart boundaries until the whole grid comes back.

Candidates need a daypart's *bounds* to be tiled (a candidate layout for a
2-hour block looks nothing like one for a 6-hour block) — but the bounds are
decided by Pass A, which today happens *inside* the same call that Pass B
does. Two ways to resolve this:

**Option A — split into two round trips.** Tunarr Scheduler calls a new
Pass-A-only endpoint, gets the `DaypartSkeleton` back, computes
`DaypartCandidate`s per block from the now-known bounds, then calls a
Pass-B-per-daypart endpoint (looped from Tunarr Scheduler's side, not
Tunabrain's) with that daypart's candidate menu attached. Architecturally the
"correct" shape — candidates are always exact for the daypart they're
offered against — but a real lift: new HTTP endpoints on both sides
(`tunabrain/api/routes.py`, `tunarr-scheduler/tunabrain.clj`), and
`orchestration.clj`'s `run-quarterly!` takes over the looping
`propose_quarterly_grid` currently does internally.

**Option B — one round trip, catalog-level candidates.** Tunarr Scheduler
precomputes candidates that are daypart-*agnostic* (e.g. "here are the
duration-feasible layouts per hour of block length, for each category") and
includes that bundle in the existing `QuarterlyGridRequest` payload.
Tunabrain's existing internal Pass A → Pass B loop consults the bundle when
it reaches each block (it already knows that block's exact bounds at that
point) rather than the LLM inventing lengths freely. Keeps today's
single-request contract; the cost is that "guaranteed feasible for this exact
daypart span" becomes "guaranteed feasible for *some* span of this length,"
a slightly weaker property, though still enforced (Tunabrain validates
against the exact bounds it has internally) rather than merely hoped for.

**Recommendation for discussion:** start with Option B. It's additive to the
existing single-request shape (no new endpoints, no restructuring of
`orchestration.clj`'s call pattern), ships independently of any HTTP contract
change, and can be validated against real catalogs before committing to
Option A's bigger lift. If Option B's slightly-weaker guarantee proves
insufficient in practice, Option A remains available as a follow-up — nothing
in Option B forecloses it. This is flagged as an open question (§5.3) rather
than decided here.

### 4.4 Changed Pass B contract (either option)

Today, `build_strip_fill_prompt` (`quarterly_grid.py:177-236`) hands the LLM
a `CatalogProfile` summary and free-form `start`/`end` string fields to
invent. Under Phase 3, the LLM's output schema narrows: it picks a
`layout_id` (or composes directly from the offered `CandidateSlot`s) and
assigns *content* — show, category, label — to each slot the candidate
already tiled. It no longer emits `start`/`end` at all; those come from the
chosen candidate's precomputed tiling. This is a schema change to Pass B's
JSON contract (`_parse_strips` in `quarterly_grid.py`), not just an addition
to the prompt text.

### 4.5 Migration path

- **Land behind a flag**, not a hard cutover — e.g. a per-channel or global
  config toggle selecting free-form vs. candidate-menu Pass B, so existing
  behavior is unaffected while the new path is validated against real
  catalogs.
- **`GridStrip`/`Grid` themselves don't change at all.** Phase 3 only changes
  *how* Pass B arrives at a strip's `start`/`end`/`content` — not the shape of
  what gets frozen and stored. Phase 1 (air-time selection) and Phase 2
  (feasibility) are both fully compatible regardless of whether a given grid
  was authored the old free-form way or the new candidate-menu way.
- Feasibility (Phase 2) remains the backstop for what candidates structurally
  can't cover: `series:`/`movie:`-by-id strips (a specific id isn't a pool, so
  there's no menu for it — the existing episode-count check still applies),
  and defense-in-depth for anything the LLM assigns that doesn't actually
  match the category it claimed.

---

## 5. Open questions (need a decision before implementation)

1. **Candidate weighting function (§4.2).** Minimum availability across a
   layout's slots, a product, or something else? Affects how strongly a thin
   bucket suppresses an entire layout versus just the slot that uses it.
2. **Histogram payload shape (§3.2).** Grow the existing
   `GET /api/catalog/aggregate` response inline (bigger payload on every call,
   including ones that don't need it) vs. a separate endpoint Tunarr Scheduler
   calls only during quarterly planning (smaller, rarer, but a new route to
   maintain)?
3. **Option A vs. B for Phase 3 (§4.3).** Recommendation above is B-first;
   confirm before any Tunabrain prompt-contract work starts, since the two
   options touch different files and neither is a strict subset of the other.
4. **Long-term status of free-form authoring (§4.5).** Does hand-authored /
   free-form grid authoring stay supported indefinitely (useful for operator
   overrides or catalogs too small for meaningful candidate menus), or is
   Phase 3's candidate menu meant to fully replace it once validated?

---

## 6. Sequencing summary

| Step | Repo(s) | Independently shippable? |
|---|---|---|
| 3.2 Histogram dimensioning | pseudovision | Yes — additive to aggregate response |
| 3.3 Wire contract mirror | tunabrain, tunarr-scheduler | Yes — optional fields |
| 3.4 Feasibility duration finding | tunarr-scheduler | Yes — pure function, own test suite |
| 4.2 `propose-daypart-candidates` | tunarr-scheduler | Yes — pure function, prototype against a real profile snapshot before touching the LLM prompt |
| 4.3/4.4 Pass B contract change | tunabrain (+ tunarr-scheduler if Option A) | No — depends on §5.3 and 4.2 |

Recommend building and testing 3.2–3.4 and 4.2 as standalone, independently
mergeable pieces (each has the same "pure function + fixture-based test suite"
shape as the existing `feasibility.clj`/`expander.clj` work) before committing
to the Pass B contract change, which is the one piece here that can't be
partially landed.
