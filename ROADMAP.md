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

### Phase 0 — Contracts ⏳ IN PROGRESS
Mirror the four+1 Tunabrain contracts as Clojure/Malli schemas with exact
snake_case JSON field names: `Content`, `CatalogProfile` (+ `ShowProfile`,
`GenreProfile`, `RuntimeBucket`), `Grid` (+ `GridStrip`, `DaypartSkeleton`,
`DaypartBlock`), `Override` (+ `OverrideScope`), `FeasibilityReport`
(+ `StripFeasibility`), `DailySlot`.
- **Deliverable:** `tunarr.scheduler.scheduling.contracts` ns + round-trip tests.

### Phase 1 — Deterministic expander 🔜 (build first after contracts)
Port `expand(grid, overrides, range_start, range_end) → DailySlot[]` as a **pure
function**: materialize (with the leading `range_start − 1 day` for overnight
strips), boundary sweep with precedence tuple
`(layer_rank, scope_specificity, priority, definition_order)`, merge adjacent
same-rule intervals, emit sorted+clipped slots, `default_content` fill.
- **Deliverable:** `…scheduling.expander` + every case from
  `tests/test_grid_expander.py` ported as Clojure tests (the golden spec).
- **Milestone:** hand-author a Grid JSON, expand a week, push to Pseudovision,
  watch TV. No Tunabrain, no aggregate endpoint yet.
- Replaces the stub `scheduling/engine.clj::schedule-week!`.

### Phase 2 — CatalogProfile assembly
Assemble a per-channel `CatalogProfile`. **Decision: source runtimes and
eligibility from Pseudovision** (see "Open decisions" below). Add a
`get-catalog-aggregate` client fn; assemble/slice per channel.

### Phase 3 — Feasibility checker
Pure `(grid, catalog-profile, horizon-start, horizon-end) → FeasibilityReport`:
per-strip `slots_required` (day-pattern × horizon), `episodes_available` by
`media_id` kind, `headroom_ratio`, status thresholds (`MARGIN = 1.2`),
base-grid overlap detection, broadcast-day coverage gaps, `overall_status`
rollup.
- **Deliverable:** `…scheduling.feasibility` + tests.

### Phase 4 — Storage
Persist the system of record (mirroring `scheduling/strategy.clj`'s executor
pattern): a `grids` table (one frozen, versioned Grid per channel+quarter,
carrying Tunabrain's `grid_id`) and an `overrides` table (per channel+month,
carrying `overrides_id`). New migrations under `resources/migrations/`.

### Phase 5 — Tunabrain client + orchestration
- Add `propose-quarterly-grid!`, `repair-quarterly-grid!`,
  `propose-monthly-overrides!` to `tunarr.scheduler.tunabrain` (reusing
  `json-post!`).
- Rework `scheduling/tasks.clj`:
  - **Quarterly:** profile → propose → feasibility → bounded repair loop
    (max ~3) → freeze + store.
  - **Monthly:** propose-monthly-overrides (frozen grid + month profile +
    operator `planned_events`) → store.
  - **Weekly:** `expand(...)` → push `DailySlot[]` to Pseudovision. **No
    Tunabrain call.**
  - **Daily:** horizon extension (unchanged).
- Repoint `http/api/scheduling.clj` handlers at the new orchestration.

### Phase 6 — GUI checkpoints (can follow)
Human review on the small artifacts: approve the `DaypartSkeleton` and the
frozen `Grid` (a list of rules) before committing. Optional endpoints to
fetch/approve a proposed Grid/Overrides and to trigger an on-demand weekly
expand+push.

### Deprecation (alongside Phases 1–5)
`scheduling/pseudovision.clj` (slot-spec translation), `scheduling/templates.clj`
(3-slot templates), and the slot-mutation parts of `scheduling/intent.clj`
become legacy. Decide whether to retire `intent.clj` or re-point natural-language
edits at Overrides. The old `strategies` table may remain for history.

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
