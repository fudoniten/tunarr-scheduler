# Tunarr Scheduler plan

Last reviewed: 2026-10-13

This is the one place for ongoing work on Tunarr Scheduler: what's being worked
on, what's next, known issues, and the decisions that still shape the code.
Delete items once they're done (git history keeps them). Cross-repo priorities
live in the [ecosystem plan](https://github.com/fudoniten/pseudovision/blob/master/PLAN.md#ecosystem).

## Now

### One timeline writer (ecosystem step 3)

PV's native engine becomes the only thing that writes a channel's timeline.
Today the quarterly run syncs the base grid onto it, but monthly overrides only
reach PV through the weekly DailySlot push, which also deletes non-manual
events.

- [ ] Teach `native_schedule.clj` to apply monthly overrides as date-bounded
      manual events for each override window (PV already protects manual
      events from rebuilds).
- [ ] Delete the weekly push: `run-weekly!`, `integration/publish-week!`,
      `publish-daily-slots!`, the PV client's `push-daily-slots!`,
      `POST /api/scheduling/weekly`, and the weekly CronJob in
      `deploy/k8s/cronjobs.yaml` (suspended 2026-10-14, so overrides don't
      apply until the first item lands). PV deletes its `daily-slots` endpoint
      in the same step.
- [ ] Keep the expander: plan preview and the review loop still use it.

### Dead code (ecosystem step 3)

- [ ] Strategies: `scheduling/strategy.clj`, `http/api/strategy.clj`,
      `llm.clj`, the `:tunarr/llm` component, the `LLM_*` config, and the
      `strategies` table.
- [ ] The TTS stub: `tts.clj`, `:tunarr/tts`, the `:tts` config. Voiced
      bumpers, if wanted later, belong in Tunabrain.
- [ ] The duplicate PV→TS import path: `media/pseudovision_collection.clj` and
      `media/sync.clj` (the `rescan` route). Keep
      `media/pseudovision_media_sync.clj` (`sync-from-pseudovision`).
- [ ] One-time migrations: `media/pseudovision_migration.clj` and the
      `/api/media/migrate-to-pseudovision` and
      `/api/media/:library/migrate-catalog-ids` routes.
- [ ] Superseded endpoints with no callers:
      `GET /api/dimensions/channel/descriptions`, `/api/catalog/channels*`,
      `/api/dimensions/clean`, `/api/media/curate-all`,
      `GET /api/channels/:id/schedule`.
- [ ] Unused `channels/sync.clj` functions (`sync-on-startup!`,
      `channels-from-config`) and the unused keys destructured in the
      http-server init (`system.clj`).

### Wiring

- [ ] Schedule `POST /api/bumpers/generate` (a CronJob per channel or one
      batch run), so bumpers are generated without a manual call.
- [ ] Add GitHub CI that runs `nix flake check` (ecosystem step 4).

## Next

### Dimension cleanup

Everything is a dimension in `media_categorization`, flattened to
`dimension:value` tags for PV. PV's side is done (`metadata_genres` is gone).
TS still has the old first-class fields.

- [ ] Stop writing `media_genres`, `media_channels`, and `media.kid_friendly`
      in `curation.core` and the PV import.
- [ ] Drop the old schema in a migration: `media.kid_friendly`, the `genre`
      and `channel` tables, `media_genres`, `media_channels`.
- [ ] Remove the `^:deprecated` specs (`::genres`, `::channel-names`,
      `::kid-friendly?`, `::classification`), catalog protocol methods, SQL
      helpers, and memory-catalog methods.
- [ ] Remove `/api/genres` and `/api/genres/:genre/media` once Marquee stops
      using `/api/genres` as a fallback.

### Bumpers

- [ ] Verify end to end on the cluster: generate for one channel, see the
      Grout upload succeed, and see PV place the bumper in a short gap with the
      "Coming up next" overlay.
- [ ] Confirm `FREESOUND_API_KEY` and `GROUT_URL` in the deployment, and source
      the CC0 music library with `tools/bumper-music/`.
- [ ] Drop TS's shared volume mount in the infra repo's deployment; uploads
      are HTTP-only now.

### Scheduling

- [ ] Make the duration-feasible candidate menu
      (`orchestration/propose-grid-via-daypart-candidates!`) the default for
      `run-quarterly!` once it's been checked against the real catalog.

## Later

- Auth on the public endpoints once development winds down.
- Keep one channel registry, in PV, instead of defining channels in TS config
  and pushing them.
- Settle on kebab-case at TS's HTTP boundaries, and consider generating the PV
  client from PV's OpenAPI spec (martian) instead of the hand-written
  `backends/pseudovision/client.clj`, about half of whose functions are unused.
- Slim TS's copy of the media catalog down to what curation needs.
- Move the dimension vocabulary out of `resources/config.edn` into data.
- `Override.mode` supports only `"replace"`; `"insert"`/`"preempt"` and richer
  scopes ("2nd Tuesday") are unbuilt.

## Known issues

- `nix flake check` may fail to fetch `reitit-schema-0.10.1.pom` even though
  it's in `deps-lock.json`. Needs a build log to tell a fetch failure from the
  resolver ignoring the cache.
- `media/pseudovision_collection.clj` invents data on import (premiere date =
  today, `kid-friendly? false`). Goes away with the duplicate import path.

## Decisions

- 2026-10-13: PV's native engine is the only timeline writer. Monthly
  overrides move into it; the weekly DailySlot push is retired.
- 2026-10-13: The TTS stub is deleted; voiced bumpers would go through
  Tunabrain.
- All AI calls go through Tunabrain. TS holds no model keys.
- PV has no dimension awareness: TS flattens dimensions to prefixed tags.
- The `CatalogProfile` comes from PV's `GET /api/catalog/aggregate`, since only
  PV knows runtimes and availability.
- Grid generation isn't gated on human approval. Operators steer it through
  per-channel guidance instead.
