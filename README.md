# Tunarr Scheduler

Tunarr Scheduler (TS) is the control plane for Pseudovision's TV channels. It's
a Clojure service that:

1. **Curates the catalog.** It pulls media items from Pseudovision, has
   Tunabrain categorize and tag them, and pushes the resulting tags back to PV.
2. **Plans each channel's schedule.** Once a quarter it has Tunabrain propose a
   frozen weekly **grid**, checks it for feasibility, and syncs it onto PV's
   native schedule engine. Once a month it stores sparse **overrides**.
3. **Generates bumpers.** It asks Tunabrain for bumper art, composes short
   clips with ffmpeg, and uploads them to Grout.

TS holds all scheduling state. Tunabrain is stateless and makes every AI call;
TS calls no model directly except the legacy strategies feature (see
[PLAN.md](PLAN.md)). Pseudovision owns the media library and the playout
timeline.

Deployed at `https://tunarr-scheduler.kube.sea.fudo.link`.

Ongoing work, open checklists, and known issues live in [PLAN.md](PLAN.md).
Conventions and pitfalls for contributors are in [AGENTS.md](AGENTS.md).

## How it fits

```
              ┌──── media items ─────┐
              ▼                      │
   Tunarr Scheduler ── tags, channels, grid ──► Pseudovision ──► streams
     │        │
     │        └── bumper uploads ──► Grout ◄── filler queries ── Pseudovision
     ▼
   Tunabrain (all LLM work: categorize, tags, grids, overrides, bumpers)
```

Marquee and pvcli read TS's catalog, dimension, plan, and job endpoints.

## Running locally

Requires Java 21+ and the Clojure CLI (`nix develop` provides both).

```sh
docker compose up -d postgres                       # optional: local PostgreSQL
clojure -M:run --config resources/config.edn
clojure -M:run --config base.edn --config local.edn --log-level debug   # layered config
```

`resources/config.edn` defaults to an in-memory catalog, a mock LLM, and
`localhost` for every service, so the service starts with no dependencies.
Options: `--config PATH` (repeatable; later files win), `--log-level LEVEL`,
`--help`.

### Configuration

| Variable | Purpose |
|---|---|
| `PSEUDOVISION_URL` | Pseudovision base URL |
| `TUNABRAIN_ENDPOINT` | Tunabrain base URL |
| `GROUT_URL` | Grout base URL; unset disables bumper upload |
| `BUMPER_OUTPUT_DIR` | Local scratch dir for composing bumpers (not shared with Grout) |
| `PSEUDOVISION_AUTO_SYNC` | Push tag edits to PV as they happen (default `true`) |
| `PSEUDOVISION_SYNC_DEBOUNCE_MS`, `_BULK_THRESHOLD`, `_BACKOFF_MS`, `_RECONCILE_HOURS` | Auto-sync tuning |
| `TZ` (or `TIME_ZONE`) | Scheduling time zone; keep it the same as PV's |
| `CATALOG_HOST`, `CATALOG_PORT`, `CATALOG_DATABASE`, `CATALOG_USER`, `CATALOG_PASSWORD` | PostgreSQL catalog connection, used when the config sets `:catalog {:type :postgresql}` |
| `PORT`, `LOG_LEVEL` | Server port (default 8080), log level |
| `LLM_*` | Only used by the legacy strategies feature |

Channels are defined under a top-level `:channels` key, supplied as a separate
config file (a Kubernetes configmap in production). The categorization
dimensions (`:categories`) in `resources/config.edn` are the controlled
vocabulary for the whole ecosystem; Grout validates against them too. Don't add
a `:channel` dimension there; its values come from `:channels`.

### Database migrations

Migrations live in `resources/migrations` and run with
[Migratus](https://github.com/yogthos/migratus) (the flake also builds a
`tunarr-scheduler-migratus` image). To migrate a local database:

```sh
clojure -Sdeps '{:deps {migratus/migratus {:mvn/version "1.6.3"}}}' \
  -M -m migratus.core migrate resources/migratus.edn
```

## Testing

```sh
clojure -M:test                                     # full suite
clojure -M:test --focus tunarr.scheduler.scheduling.expander-test
nix flake check                                     # the same suite against locked deps
```

The tests in `test_postgres_only/` need a real PostgreSQL and aren't part of the
default run.

## How scheduling works

| Cadence | Endpoint | What happens |
|---|---|---|
| Quarterly | `POST /api/scheduling/quarterly` | Build a `CatalogProfile` from PV's `/api/catalog/aggregate` → Tunabrain proposes a grid → deterministic feasibility check → bounded repair loop → optional taste review → freeze. For the current quarter, sync the grid onto PV's native schedule. |
| Monthly | `POST /api/scheduling/monthly` | Tunabrain proposes sparse overrides against the frozen grid; TS stores them. |
| Weekly | `POST /api/scheduling/weekly` | Expand grid + overrides into dated `DailySlot`s and push them to PV. Being retired; see [PLAN.md](PLAN.md). |
| Daily | `POST /api/scheduling/daily` | Ask PV to extend each channel's playout horizon. |

Kubernetes CronJobs call these endpoints; TS runs no in-process scheduler. See
[deploy/k8s/README.md](deploy/k8s/README.md) for the manifests and for running
a quarter ahead of time.

The wire contracts (`CatalogProfile`, `Grid`, `Override`, `FeasibilityReport`,
`DailySlot`) are defined by Tunabrain's Pydantic models and mirrored here as
Malli schemas in `scheduling/contracts.clj`. The prose spec, including the
expander's precedence rules, is
[Tunabrain's `docs/scheduling-grid-spec.md`](https://github.com/fudoniten/tunabrain/blob/master/docs/scheduling-grid-spec.md).

Operators steer generation per channel through
`/api/scheduling/channels/:channel/guidance` (themes, planned events, strategic
guidance), and can read the frozen grid, overrides, preview, and plan from the
same prefix.

## Catalog and tag sync

TS's catalog (in-memory or PostgreSQL) mirrors PV's media items and adds
categorization and tags. Dimensions are flattened to `dimension:value` tags
when pushed to PV.

With auto-sync on, every item-level tag or category write marks the item dirty,
and a background worker pushes dirty items to PV after a short debounce. Global
tag operations (rename, delete, purge) trigger a full reconcile, and a periodic
reconcile (default every 24 h) catches anything missed. The
`POST /api/media/:library/sync-pseudovision-tags` endpoint still forces a
full-library sync.

## Bumpers

`POST /api/bumpers/generate?channel=X&count=N&durations=5,10,15` asks Tunabrain
for an image, picks a CC0 music track (or goes silent), composes a Ken Burns
clip with ffmpeg into `BUMPER_OUTPUT_DIR`, and uploads it to Grout as a
multipart `POST /grout/media`. PV's gap filler then pulls bumpers from Grout.
[`tools/bumper-music/`](tools/bumper-music/README.md) sources the music library
from Freesound.

## Source layout

```
src/tunarr/scheduler/
├── main.clj, system.clj, config.clj   entry point, Integrant system, layered config
├── http/                              reitit routes, schemas, one api/ ns per resource
├── scheduling/                        contracts, expander, feasibility, candidates,
│                                      orchestration, native_schedule, review,
│                                      storage, plans, tasks (cron entry points)
├── media/                             catalog protocol + SQL/memory impls, PV sync
├── curation/                          LLM categorization, tags, dimension validation
├── channels/                          channel config → PV channel sync
├── backends/{pseudovision,grout}/     HTTP clients
├── tunabrain.clj                      Tunabrain client
└── bumpers.clj                        bumper pipeline
```

The full HTTP API is at `GET /openapi.json`; `GET /healthz` is the readiness
probe. `archive/` holds one finished design record (duration-aware scheduling)
that code comments cite by section.
