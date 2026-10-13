# AGENTS.md — Tunarr Scheduler

> Working notes for AI agents and humans changing this repo. Start with
> [README.md](README.md) (what TS does, how to run and test it, how scheduling
> works) and [PLAN.md](PLAN.md) (what's being worked on).

## Ground rules

- **All AI calls go through Tunabrain.** TS holds no model keys. The one
  exception, `llm.clj` and the strategies feature, is scheduled for deletion;
  don't build on it.
- **Pseudovision owns media and the timeline.** TS reads media items from PV
  and writes tags, channels, and schedules to it. Jellyfin is no longer a TS
  dependency; PV scans it.
- **The Tunabrain Pydantic models are the source of truth for the scheduling
  contracts.** When one changes, update `scheduling/contracts.clj` (Malli) in
  the same change, and keep the expander golden test
  (`expander_golden_test.clj`, ported from Tunabrain's
  `tests/test_grid_expander.py`) passing.
- **Expansion and feasibility are pure.** No I/O, randomness, or LLM calls in
  `expander.clj`, `feasibility.clj`, or `candidates.clj`. Episode rotation
  happens at air time in PV, not here.
- **The grid is frozen.** Monthly and weekly steps never mutate it; re-freezing
  inserts a new version.

## Common pitfalls

1. **Channel identity has five spellings:** the config slug (`goldenreels`),
   the display name (`Golden Reels`), the TS channel UUID, the PV UUID, and the
   PV integer id. HTTP routes take the slug. Storage (`grids`, `overrides`,
   `channel_guidance`) is keyed by the **TS channel UUID**: pass
   `::media/channel-uuid` to storage functions, never
   `::media/channel-fullname`.
2. **PV emits kebab-case JSON** (`has-more`, not `has_more`), while the
   scheduling contracts and Tunabrain use snake_case. The conversion happens at
   the `scheduling/integration.clj` boundary; don't sprinkle it elsewhere.
3. **Tunabrain can return explicit `null` in override scope fields.** Strip
   them before validating against `Override` (see `orchestration.clj`).
4. **`media.channel_names` is empty.** Channel membership lives in
   `media_categorization` (the `channel` dimension) and reaches PV as the
   `channel:<slug>` tag. Use the dimensions endpoints, not the legacy fields.
5. **Define routes at the top level of `http/routes.clj`.** The OpenAPI spec is
   generated from the route table at boot, so routes added elsewhere (for
   example inside a test's `with-redefs`) won't appear in it.
6. **Quarterly grid proposals are slow** (about two minutes per channel).
   Keep the Tunabrain socket timeout at two minutes or more.
7. **Don't add a `:channel` dimension to `:categories`.** Its values are
   derived from the top-level `:channels` config.
8. **Ignore the `^:deprecated` genre and channel functions** in `media.clj`,
   `catalog.clj`, and `sql_catalog.clj`. They're slated for removal (see the
   dimension cleanup in [PLAN.md](PLAN.md)); new code should use dimensions.
