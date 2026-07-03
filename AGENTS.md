# AGENTS.md — Tunarr Scheduler

> Working notes for AI agents and humans working on this repo.
> For a deeper tour, see [README.md](README.md), [ROADMAP.md](ROADMAP.md),
> and `PSEUDOVISION_INTEGRATION.md`.

## What this is

Tunarr Scheduler is the **control plane** for the Fudo stack. It is a Clojure
service that:

1. Pulls media metadata from **Jellyfin** (catalog sync).
2. Calls **Tunabrain** (LLM gateway) to tag and categorise media.
3. Computes and freezes **quarterly grids + monthly overrides** per channel.
4. Runs a deterministic **weekly expander** that projects the grid onto a
   date range and pushes concrete DailySlots to **Pseudovision**.
5. Drives the **bumper pipeline** (LLM script → TTS → ffmpeg → Grout).

Pseudovision owns the playout timeline. Tunarr Scheduler decides *what* to
schedule. Tunabrain supplies the LLM reasoning. Tunarr Scheduler is stateful;
Tunabrain is stateless.

## How it fits in the ecosystem

```
   Jellyfin ──►  Tunarr Scheduler  ──► Pseudovision  ──► HLS stream
                  │       │
                  ▼       ▼
                Tunabrain  Grout (bumpers)
                (LLM)      (filler)
                  ▲
                  │
                 Marquee (UI, optional)
```

- **Upstream**: Jellyfin (`jellyfin.kube.sea.fudo.link`) is the source of
  truth for media. Tunarr Scheduler reads library metadata, episodes, and tags
  from it.
- **Outbound writer**: Pseudovision
  (`pseudovision.kube.sea.fudo.link`) is the only writer for `daily-slots` and
  channel-tags in the live cluster. Tunarr Scheduler never edits PV directly
  outside of these two flows.
- **LLM dependency**: Tunabrain (`tunabrain.kube.sea.fudo.link`) is
  invoked for tagging, channel-mapping, propose-grid, repair-grid, and
  propose-overrides.
- **Bumper target**: Grout (`grout.pseudovision.svc.cluster.local:8080`,
  in-cluster only) for LLM-generated bumpers. Tunarr Scheduler's `8df074d`
  switched the bumper pipeline from Jellyfin to Grout.

## Live endpoints (cluster)

| Service | URL | Notes |
|---|---|---|
| Public HTTPS | `https://tunarr-scheduler.kube.sea.fudo.link` | Ingress via cert-manager |
| Version | `GET /api/version` | `{git-commit, git-timestamp, version-tag}` |
| OpenAPI | `GET /openapi.json` | Reitit-generated; ~30 endpoints (5 cron-driven + 5 scheduling + dimensions + tags + jobs) |
| Health | none at root | Use `/api/version` as a liveness probe |
| In-cluster | `tunarr-scheduler.arr.svc.cluster.local:3000` | In the `arr` namespace; Pseudovision calls into this when migrating media |

**Deployed as of 2026-07-03:** `c08c4c0` (most recent, includes the Grout
bumper migration). Live cluster and `upstream/master` are at the same SHA.

## Local development

```bash
# Run
clojure -M:run --config resources/config.edn
clojure -M:run --config resources/config.edn --log-level debug
clojure -M:run --config base.edn --config prod-overrides.edn  # layered config

# Tests (eftest)
clojure -M:test
clojure -M:test --focus tunarr.scheduler.scheduling.expander-test

# nREPL
clojure -M:repl
```

Tooling:

- **Clojure CLI** (deps.edn-based)
- **Java 21+**
- The service expects a `pseudovision` config block pointing at PV's base URL
  and a `tunabrain` config block. See `resources/config.edn`.

## Source layout

```
src/tunarr/scheduler/
├── main.clj              ; CLI / system boot
├── system.clj            ; integrant system map (wires everything together)
├── config.clj            ; Aero + layered config loading
├── http/                 ; reitit routes
│   ├── core.clj          ; the router
│   ├── api/              ; one ns per resource
│   │   ├── scheduling.clj     ; /api/scheduling/{daily|weekly|monthly|quarterly}
│   │   ├── plans.clj          ; /api/scheduling/channels/{ch}/{grid|overrides|plan|preview|guidance}
│   │   ├── media.clj          ; recategorize, retag, sync-pseudovision
│   │   ├── dimensions.clj     ; /api/dimensions/*
│   │   ├── tags.clj           ; /api/tags
│   │   ├── jobs.clj           ; /api/jobs/* (async job runner)
│   │   └── ...
│   └── ...
├── scheduling/
│   ├── orchestration.clj ; run-quarterly! / run-monthly! / run-weekly!
│   ├── integration.clj   ; publish-week! / publish-daily-slots!  (talks to PV)
│   ├── expander.clj      ; deterministic grid + overrides → DailySlot[]
│   ├── contracts.clj     ; Malli schemas (CatalogProfile, Grid, Override, DailySlot)
│   ├── feasibility.clj   ; pre-flight capacity checks
│   ├── plans.clj         ; preview + storage layer for frozen grids
│   └── tasks.clj         ; cron-driven task entry points
├── backends/
│   └── pseudovision/
│       └── client.clj    ; the PV HTTP client; push-daily-slots! lives here
├── curation/             ; LLM-driven tagging + categorisation
├── media/                ; Jellyfin client + per-library operations
├── channels/             ; channel definitions, config keys
├── tunabrain.clj         ; Tunabrain HTTP client
├── bumpers.clj           ; bumper orchestration
└── tts.clj               ; TTS abstraction (used by bumpers)
```

## Public API surface (high-traffic endpoints)

The 5 cron-driven endpoints (per the [tunarr-scheduler-scheduling-redesign.md](references/tunarr-scheduler-scheduling-redesign.md)):

| Endpoint | Purpose | Caller |
|---|---|---|
| `POST /api/scheduling/daily` | Extend the playout horizon (1-2 days) | K8s CronJob (frequent) |
| `POST /api/scheduling/weekly` | Re-apply schedule templates to every channel (deterministic) | K8s CronJob (weekly) |
| `POST /api/scheduling/monthly` | LLM-proposed sparse overrides | K8s CronJob (monthly) |
| `POST /api/scheduling/quarterly` | LLM-proposed frozen weekly grid + repair loop | K8s CronJob (quarterly) |
| `GET /api/scheduling/channels` | List channels with stored plan/guidance | Marquee |

Plus the per-channel control plane (under `/api/scheduling/channels/{slug}/`):
`grid`, `overrides`, `plan`, `preview`, `guidance` (PUT/GET).

The dimensions and tags endpoints are the **truth source** for channel-tagged
media:

- `GET /api/dimensions` — `[{name, value-count}, ...]`
- `GET /api/dimensions/channel/values` — `[{value, usage-count}, ...]`
- `GET /api/dimensions/channel/values/{slug}/media` — concrete media for a
  channel. **Use this, not `GET /api/catalog/aggregate?channel=X`** — the
  latter lives in PV and was the source of Issue 4.
- `GET /api/tags`, `GET /api/tags/{tag}/media` — flat tag inventory (no
  `channel:*` prefix in tag names; the `:` prefix is a display convention
  in dimensions, not stored in `media_tags`).

Full schema: `GET /openapi.json`.

## Common pitfalls

1. **Pattern 8a — sync the fork before debugging.** `~/repos/tunarr-scheduler`
   is the `fudohermes` personal fork. Active work happens on
   `fudoniten/tunarr-scheduler` upstream. Local WIP (uncommitted files, draft
   branches) is **not** what's deployed. Always run
   `git fetch upstream && git log upstream/master --since="14 days ago"` first.
   The Jul 2026 changes digest (`references/tunarr-scheduler-july-2026-changes.md`)
   is the second stop.
2. **The 4e8fb6b WARN is the most common "I never saw this before" cause.**
   When weekly scheduling silently fails, this WARN surfaces it:
   `publish-week!: pushed daily slots but NONE were ingested`. Look at it
   before assuming a code regression. If it's there, the failure is in
   Pseudovision's `daily-slots` handler (e.g. the `category_filters` show-vs-episode
   bug fixed in PV PR #114), not in this repo.
3. **`channel-tag` injection is the cause AND the cure.** Scheduler adds
   `channel:<slug>` to every slot's `category_filters` (via
   `tasks.clj:39 channel-catalog-tag` and `integration.clj:publish-daily-slots!`).
   If PV's filter doesn't honour it, every weekly push is silently rejected.
   This is the same plumbing that exposes the underlying PV bug, and fixing
   the PV side makes scheduling work again.
4. **`channel-names` in `media_items` is empty.** The legacy hardcoded field
   is no longer the source of truth. Channel membership lives in
   `media_categorization` (the dimension table) and is flattened into
   `media_tags` as the `channel:<slug>` tag at sync time. Don't read
   `media.channel_names` to filter — use the dimensions endpoint.
5. **`channel_ids` are slug strings, not integers.** Tunarr Scheduler's
   `channel` query param takes the config-key slug (`goldenreels`), not the
   display name (`Golden Reels`) and not PV's integer id. The `channel_id`
   query param takes PV's integer id.
6. **Tunabrain responses can have explicit `null` in scope fields.** Strip
   them before validating against `Override` (commit `1caf909`). The
   `Tunabrain → Override` step in `scheduling/orchestration.clj` is the
   place to look.
7. **Heredocs and shell-interpretation can corrupt OpenAPI generation.** The
   reitit OpenAPI spec is generated at boot from the route table; if routes
   are added inside `with-redefs` blocks in tests, they won't appear in the
   spec. Define routes at the top level, then add the handler impls.
8. **Tunabrain is now in `arr` namespace, not `media`.** When looking up
   the service, use `tunabrain.arr.svc.cluster.local:5546` (in-cluster) or
   `tunabrain.kube.sea.fudo.link` (ingress). The legacy `media` namespace
   service was a sidecar-only stub that had no endpoints.

## Where to look next

- `README.md` — features, layout, getting started
- `ROADMAP.md` — current phase of the layered grid scheduling
- `PSEUDOVISION_INTEGRATION.md` — why this repo talks directly to PV instead
  of going through Jellyfin
- `references/tunarr-scheduler-scheduling-redesign.md` — the batch→cron
  pipeline (commit `ba6f7c0`)
- `references/tunarr-scheduler-july-2026-changes.md` — 14-day commit digest
  (always re-run `git log upstream/master --since="14 days ago"` for current)
- `references/catalog-aggregate-channel-filter-bug.md` — Issue 4 (PV-side)
- `references/handoff-tunarr-pseudovision.md` (in tunabrain repo) — the
  authoritative spec for the scheduler↔tunabrain contract
