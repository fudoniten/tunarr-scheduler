# Roadmap — Layered Grid Scheduling

**Status:** active phase (supersedes the old 3-level batch roadmap, removed June 2026)

This roadmap covers Tunarr Scheduler's half of the **layered "grid" scheduling**
design. The authoritative cross-system spec lives in the **tunabrain** repo:

- `docs/handoff-tunarr-pseudovision.md` — the handoff this roadmap implements
- `docs/scheduling-grid-spec.md` — full prose spec (expander algorithm §6)
- `src/tunabrain/scheduling/grid.py` — the canonical Pydantic contracts
- `tests/test_grid_expander.py` — the golden conformance suite to port

The Tunabrain side is already implemented (branch `claude/elegant-bohr-fkf53n`,
PR #36). This document tracks the Tunarr Scheduler counterpart.

---

## Why this replaces the old design

The previous approach (`SCHEDULING.md`, now removed) regenerated whole
Pseudovision `schedules` + `slots` on every monthly/quarterly run — a large
batch operation that performed poorly and re-derived consistency from the LLM
each time.

The new model **authors once and projects many times**:

1. **Quarterly** — an LLM proposes a *frozen weekly Grid* of recurring rules
   ("weekdays 17:00–18:00 → Seinfeld").
2. **Monthly** — the LLM proposes *sparse Overrides* (deltas: "Sat the 10th:
   Cheers marathon").
3. **Weekly** — a **deterministic, LLM-free expander** projects
   `(frozen grid + sparse overrides + dates)` into concrete dated `DailySlot`s.

The grid never changes week to week; only overrides cause variation. Consistency
is **structural**, not something the LLM re-derives. Tunarr Scheduler holds all
state; Tunabrain is stateless; the LLM never sees raw media and never does
capacity math.

```
Pseudovision            Tunarr Scheduler (control plane, stateful)        Tunabrain (stateless LLM)
────────────            ──────────────────────────────────────────       ─────────────────────────
catalog aggregate ────► assemble CatalogProfile ────────────────────────► propose-quarterly-grid
                            │
                        feasibility check ◄── Grid ─────────────────────  ◄── Grid
                            │ shortfalls
                            └── FeasibilityReport ─────────────────────►   repair-quarterly-grid
                            │                                          ◄── revised Grid
                        store frozen Grid
                            │
                        (per month) ──────────────────────────────────►   propose-monthly-overrides
                            │                                          ◄── Override[]
                        store Overrides
                            │
                        expand(grid, overrides, week) ── DailySlot[] ──►   (no Tunabrain call)
push concrete slots ◄───────┘
+ resolve media_id → episode
```

---

## Key invariants (must hold throughout)

- **Expansion is pure and deterministic.** Same `(grid, overrides, dates)` ⇒
  identical slots, always. No randomness in *structure*.
- **The grid is frozen.** Monthly/weekly steps never mutate it; all week-to-week
  variation comes from overrides.
- **The LLM never does capacity math.** Arithmetic lives in the feasibility
  checker; the LLM sees only the `CatalogProfile`.
- **Episode rotation happens at air time in Pseudovision** via
  `media_selection_strategy`, not in the expander.

---

## Phases

Build order follows the deterministic spine first — a hand-authored grid can be
expanded and pushed to a screen before any LLM or Pseudovision-aggregate work.

### Phase 0 — Contracts ✅ DONE
Mirror the four+1 Tunabrain contracts as Clojure/Malli schemas with exact
snake_case JSON field names: `Content`, `CatalogProfile` (+ `ShowProfile`,
`GenreProfile`, `RuntimeBucket`), `Grid` (+ `GridStrip`, `DaypartSkeleton`,
`DaypartBlock`), `Override` (+ `OverrideScope`), `FeasibilityReport`
(+ `StripFeasibility`), `DailySlot`.
- **Deliverable:** `tunarr.scheduler.scheduling.contracts` ns + round-trip tests.

### Phase 1 — Deterministic expander ✅ DONE
`expand(grid, overrides, range_start, range_end) → DailySlot[]` implemented as a
**pure function** in `scheduling/expander.clj`, reconciled against the reference
`expander.py`: materialize (with the leading `range_start − 1 day` for overnight
strips), boundary sweep with precedence tuple
`(layer_rank, scope_specificity, priority, definition_order)`, merge adjacent
intervals won by the same rule_id, emit sorted+clipped slots, `default_content`
fill.
- **Done:** `…scheduling.expander` + two test namespaces:
  `expander_golden_test.clj` (tunabrain `tests/test_grid_expander.py` ported
  verbatim — the cross-language conformance lock) and `expander_test.clj`
  (extra cases: cross-midnight, leading-day cover, priority ties,
  interior-boundary re-merge, default-only grid). 22 tests / 121 assertions
  green across the scheduling namespaces.
- **Remaining:** wire the hand-author-grid → expand-a-week → push milestone once
  DailySlot ingestion is settled; retire the stub
  `scheduling/engine.clj::schedule-week!`.

### Phase 2 — CatalogProfile assembly ✅ DONE
Sourced from Pseudovision's `GET /api/catalog/aggregate`. Pseudovision speaks
kebab-case; Tunabrain + the internal contracts speak snake_case, so the
`scheduling/integration.clj` boundary owns the deep kebab↔snake conversion.
`integration/fetch-catalog-profile` fetches, converts, and validates against
`contracts/CatalogProfile`. (Contract tweak: `RuntimeBucket.max_minutes` is now
nullable for the open-ended top bucket.) Tested in `integration_test.clj`.

### Phase 3 — Feasibility checker ✅ DONE
Pure `(grid, catalog-profile, horizon-start, horizon-end) → FeasibilityReport`
in `scheduling/feasibility.clj`: per-strip `slots_required` (day-pattern ×
horizon), `episodes_available` by `media_id` kind (`series:` sequential vs.
pooled, `random:` pool floor, `movie:` repeat check), `headroom_ratio`, status
thresholds (`margin = 1.2`, `random-pool-floor = 10`), base-grid overlap
detection (with cross-midnight handling), broadcast-day coverage gaps (only when
no `default_content`), `overall_status` rollup.
- **Done:** `…scheduling.feasibility` + `feasibility_test.clj` (13 tests). Day
  helpers extracted to a shared `scheduling/calendar.clj` used by both expander
  and checker. 32 scheduling tests / 153 assertions green.
- **Local policy knobs** (`margin`, `random-pool-floor`) and the random-category
  → catalog `genres` lookup are judgment calls with no upstream reference;
  reconcile if tunabrain ever ships a checker.

### Phase 4 — Storage ✅ DONE
Persist the system of record in `scheduling/storage.clj` (mirroring
`scheduling/strategy.clj`'s executor + JSON-column pattern): a `grids` table
(one frozen Grid per channel+quarter+year, versioned and immutable — re-freezing
inserts a new version and supersedes the prior, carrying Tunabrain's `grid_id`
and the FeasibilityReport snapshot) and an `overrides` table (per channel+month,
versioned, carrying `overrides_id`). Stored Grid/Override JSON is validated
against the contracts before insert and round-trips exactly.
- **Done:** migration `20260625-001-grids` (+ down), `…scheduling.storage`
  (`freeze-grid!`/`current-grid`/`get-grid`/`list-grids`,
  `store-overrides!`/`current-overrides`/`list-overrides`), and
  `storage_test.clj` (9 tests over an in-memory H2-backed executor: read-back,
  versioning/supersede, key independence, validation rejection, feasibility
  snapshot, empty-override sets). 41 scheduling tests / 179 assertions green.
- Columns `cal_year`/`cal_month` avoid reserved-word clashes (H2); tests fold
  H2 identifiers to lower case to mirror Postgres.

### Phase 5 — Tunabrain client + orchestration ✅ DONE
- **Client:** `propose-quarterly-grid!`, `repair-quarterly-grid!`,
  `propose-monthly-overrides!` in `tunarr.scheduler.tunabrain` (reusing
  `json-post!`), with pure request builders per handoff §5.1–5.3 and lenient
  contract-validation of responses (`tunabrain_scheduling_test.clj`).
- **Orchestration** `scheduling/orchestration.clj`:
  - **Quarterly** `run-quarterly!`: fetch CatalogProfile → propose →
    `feasibility/check` over the quarter → bounded repair loop (default 3) →
    freeze + store with the feasibility snapshot. Pulls operator guidance
    (`quarterly_theme`/`strategic_guidance`) into the proposal.
  - **Monthly** `run-monthly!`: propose-monthly-overrides against the frozen
    grid (with `monthly_theme`/`planned_events`/`strategic_guidance` guidance) →
    store. Empty override sets are normal.
  - The three external calls (profile fetch + two proposals) are injected via the
    components map (defaulting to the real impls), so tests stub without global
    redefs. `orchestration_test.clj` (7 tests).
  - **Weekly** publish lives in `integration/publish-week!` (expand stored grid +
    overrides → kebab-case → `POST /daily-slots`; no Tunabrain call).
  - **Daily** horizon extension is unchanged (old `tasks.clj`).
- **Cron wiring DONE:** `tasks.clj` rewritten to drive the new pipeline per
  channel — `run-daily!` (horizon), `run-weekly!` (expand + publish via
  `integration/publish-week!`), `run-monthly!`/`run-quarterly!` (orchestration).
  Channels come from config (`::media/channel-fullname` keys the stored
  grids/overrides + is the Tunabrain channel name; `::media/channel-id` UUID
  resolves to the Pseudovision integer id for the DailySlot push). The
  `http/api/scheduling.clj` cron handlers return per-channel results.

### Batch-path deprecation ✅ DONE
Removed `scheduling/pseudovision.clj` (slot-spec translation),
`scheduling/templates.clj` (3-slot templates), `scheduling/intent.clj` +
`http/api/intent.clj` (NL slot editing), and their routes/handlers/schemas (the
`POST /schedule`, `apply-template(s)`, and `/intent` endpoints; `GET /schedule`
is kept). The old `strategies` table/endpoints remain (independent feature,
consumed by Marquee).

### Phase 6 — UI access + operator input ✅ DONE (non-gating)
Per the product call, generation is **not** gated on human approval; instead the
UI gets read access to the plans plus a per-channel manual-input surface that
*feeds* generation.
- **Storage:** migration `20260626-001-channel-guidance` + `channel_guidance`
  table; `storage/get-guidance`/`set-guidance!` (upsert, partial update)/
  `list-guidance`/`planned-channels`.
- **Read service** `scheduling/plans.clj`: calendar helpers (`quarter-of`,
  `month-of`, `months-in-range`), `preview` (expand the stored grid + the active
  overrides for every month the window touches — no Tunabrain call), and
  `dashboard` (grid + feasibility snapshot + current overrides + guidance).
- **HTTP** `http/api/plans.clj` + routes under `/api/scheduling/channels`:
  `GET /channels`, `GET /:channel/grid` (+`/grids`),
  `GET /:channel/overrides` (+`/overrides/history`), `GET /:channel/preview`,
  `GET /:channel/plan`, and `GET`/`PUT /:channel/guidance`. Schemas in
  `http/schemas.clj`.
- The guidance fields (`strategic_guidance`, `quarterly_theme`/`monthly_theme`,
  `planned_events`) line up 1:1 with the Tunabrain request builders, so the
  orchestration (Phase 5) will pull them into propose-* calls.
- `plans_test.clj` (7 tests); full ring handler assembles without route
  conflicts. 55 scheduling tests / 232 assertions green.

---

## Open decisions

### CatalogProfile source — recommend Pseudovision aggregate endpoint
The local SQL catalog (`media/sql_catalog.clj`) has series/episode counts and
genres, but **no runtime field** (`media.clj` has no `::runtime`) and **no
watched/eligibility state** — both of which live in Pseudovision.
`avg_runtime_minutes` and `available_episode_count` directly drive the
feasibility capacity math, so the rollup must come from Pseudovision's
`GET /api/catalog/aggregate` (handoff §3.1).

**Plan:** treat the Pseudovision aggregate endpoint as the source of truth.
To avoid blocking the deterministic spine (Phases 0–1, plus a hand-authored
grid), keep `CatalogProfile` assembly behind a small protocol so a stub source
(eligibility = all, runtimes from any available metadata) can be used for tests
until the Pseudovision endpoint lands. Do **not** duplicate capacity math
locally beyond the feasibility checker.

### DailySlot ingestion — recommend a dedicated dated-slot push, not manual events
The current Pseudovision client has no `DailySlot[]` push. The right contract is
a per-channel endpoint that accepts the dated `DailySlot[]` stream (`media_id` +
`media_selection_strategy` + window) where **Pseudovision resolves
`media_id` → concrete episode at air time** (handoff §2.5/§3.3). This preserves
the invariant that expansion is pure and episode rotation happens at air time.

Reject the `inject-manual-event!` path: it forces Tunarr to pick concrete
episodes (breaking purity) and bypasses Pseudovision's rotation. Reject
re-deriving slots through Pseudovision's own schedule engine: that reintroduces
the PV-side variability the expander exists to remove.

**Action:** confirm/define the exact route + payload with Pseudovision, then add
a thin `push-daily-slots!` client fn. Until then, the expander output is the
stable internal artifact and the push is an adapter.

---

## Related documentation

- `PSEUDOVISION_INTEGRATION.md` — Pseudovision integration design
- `PSEUDOVISION_SYNC.md` — tag sync
- tunabrain `docs/handoff-tunarr-pseudovision.md` — authoritative handoff spec
