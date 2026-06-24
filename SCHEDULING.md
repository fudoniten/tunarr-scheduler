# TV Scheduling System Design — Layered Grid

This describes how Tunarr Scheduler programs Pseudovision channels using a
**layered "grid"** model. It supersedes the earlier 3-level batch design (which
regenerated whole schedules each cadence and performed poorly).

> **Authoritative contracts** live in the **tunabrain** repo:
> `docs/scheduling-grid-spec.md` (prose, incl. the expander algorithm §6),
> `src/tunabrain/scheduling/grid.py` (Pydantic models), and
> `tests/test_grid_expander.py` (golden conformance suite). JSON field names on
> the wire must match those exactly. See also `ROADMAP.md` for phased delivery.

## Philosophy

- **Author once, project many times.** A schedule is authored as a small set of
  rules and projected deterministically onto any week.
- **Consistency is structural.** A frozen grid guarantees week-to-week sameness;
  the LLM never re-derives it.
- **The LLM proposes rules, not slots.** It sees only a summarized
  `CatalogProfile` and never does capacity arithmetic.
- **Tunarr holds all state; Tunabrain is stateless.**
- **Always something playing.** `default_content` and Pseudovision fallback
  cover any gap.

## The three cadences

| Cadence   | Who        | Produces            | LLM? |
|-----------|------------|---------------------|------|
| Quarterly | Tunabrain  | frozen **Grid**     | yes (propose + repair) |
| Monthly   | Tunabrain  | sparse **Override[]** | yes |
| Weekly    | Tunarr     | **DailySlot[]**     | **no** — pure expander |

```
catalog aggregate ─► CatalogProfile ─► propose-quarterly-grid ─► Grid
                                          │
                     feasibility check ◄──┤ (deterministic, no LLM)
                          │ shortfalls
                          └─► repair-quarterly-grid ─► revised Grid ─► freeze + store
                                          │
                     month ─► propose-monthly-overrides ─► Override[] ─► store
                                          │
                     week  ─► expand(grid, overrides, dates) ─► DailySlot[] ─► Pseudovision
                                          (resolves media_id → episode at air time)
```

## Contracts (mirrored as Clojure/Malli in `scheduling.contracts`)

- **CatalogProfile** — the *shape* of a channel's library (per-series counts,
  genres, runtime histogram, `avg_runtime_minutes`, `available_episode_count`).
  Never the raw items; sized the same regardless of library size.
- **Grid** — `broadcast_day_start`, a `DaypartSkeleton` of `DaypartBlock`s, a
  list of `GridStrip`s, and `default_content`. A strip is a recurring rule:
  days pattern (`daily` / `weekdays` / `weekends` / explicit list), `start`/`end`
  (`HH:MM`, `end <= start` ⇒ crosses midnight), `Content`, `priority`, `daypart`.
- **Override** — a sparse delta with a `scope` that is *exactly one of*
  `{date}` or `{days, effective_start, effective_end}`, a time window, `Content`,
  `mode`, `priority`.
- **FeasibilityReport** — per-strip capacity findings, overlaps, uncovered
  intervals, and an `overall_status` (`ok` / `warnings` / `blocked`).
- **DailySlot** — the concrete dated output (`start_time`, `end_time`,
  `media_id`, `media_selection_strategy`, `category_filters`, `notes`).

`Content` carries `media_id` (`series:<id>`, `movie:<id>`, `random:<category>`),
`strategy`, `marathon`, `category_filters`, `label`, `notes`.

## The expander (deterministic spine)

`expand(grid, overrides, range_start, range_end) → DailySlot[]` is a pure
function — no I/O, no randomness, no LLM:

1. **Materialize** every strip and override over `[range_start − 1 day,
   range_end)` (the leading day lets an overnight strip cover the early hours of
   `range_start`) into absolute `[start, end)` intervals + a precedence tuple.
2. **Sweep** all boundary points; for each elementary interval the
   highest-precedence candidate that *fully covers* it wins, else
   `default_content` (or a gap).
3. **Merge** adjacent elementary intervals won by the same rule.
4. **Emit** `DailySlot`s sorted by start, clipped to `[range_start, range_end)`.

**Precedence tuple** (lexicographic, higher wins):
`(layer_rank, scope_specificity, priority, definition_order)` —
`layer_rank`: strip 0, override 1; `scope_specificity`: specific date 3,
explicit weekday list 2, named group 1, daily 0.

## Feasibility (deterministic, no LLM)

`(grid, catalog_profile, horizon_start, horizon_end) → FeasibilityReport`.
`slots_required` = airings of a strip over the horizon (day-pattern matches).
By `media_id` kind: `series:` sequential wants `available ≥ slots_required`
(`shortfall` if under, `tight` under `slots_required × MARGIN` with `MARGIN=1.2`);
`random:` pools tolerate repeats; `movie:` flags repeated airings. Plus base-grid
overlap detection and broadcast-day coverage gaps. `blocked` if any shortfall.

## Invariants

- Expansion is pure and deterministic.
- The grid is frozen; only overrides cause week-to-week variation.
- The LLM never sees raw media and never does capacity math.
- Episode rotation happens at air time in Pseudovision via
  `media_selection_strategy` — not a schedule change.
