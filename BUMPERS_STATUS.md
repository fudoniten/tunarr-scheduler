# Bumper Generation Pipeline — Status & Roadmap

> Last updated: 2026-07-01

## Goal

Implement **LLM-generated channel bumpers** for Pseudovision — short (≤15s) video clips that fill scheduling gaps between programming, with a "Coming up next: <title>" text overlay.

## Architecture

Bumpers now flow into **Grout**, the filler-content store, via its intake API —
replacing the old Jellyfin scan → poll → PV-collection registration path.

```
┌─────────────────┐     ┌──────────────┐     ┌─────────────┐     ┌──────────────────┐
│  Tunarr Scheduler│────▶│  Tunabrain   │────▶│  TS ffmpeg  │────▶│  Grout staging   │
│  (orchestrator)  │     │  (LLM + image)│     │  (compose)   │     │  (GROUT_MEDIA_DIR)│
└─────────────────┘     └──────────────┘     └─────────────┘     └──────────────────┘
         │                                                            │
         │   POST /grout/media (path, kind=bumper, channel, tags)     │
         └────────────────────────▶┌──────────────┐◀──────────────────┘
                                    │    Grout     │  hash → ffprobe →
                                    │ (intake+store)│  normalise → store → index
                                    └──────────────┘
                                           │  GET /grout/media?channel=…&min_ms=…
                                           ▼
                                    ┌──────────────┐
                                    │ Pseudovision │◀── gap filler queries Grout,
                                    │  (playout)   │    injects bumpers w/ overlay
                                    └──────────────┘
```

## Key Design Decisions

- **All AI calls go through Tunabrain** — TS holds no OpenRouter API key
- **Duration buckets**: 5s / 10s / 15s so gaps of varying small sizes can be filled cleanly. (Grout stores ffprobed `duration-ms` on the Media, so PV queries by `min_ms`/`max_ms` — the bucket is a generation-side convenience, not a stored tag.)
- **CC0 music library** via Freesound API (not AI music generation — cheaper, faster, legally cleaner)
- **Storage via Grout** (was Jellyfin): TS composes to a staging dir under `GROUT_MEDIA_DIR` on the shared mount, then `POST /grout/media` hands Grout the path. Grout hashes → ffprobes → normalises (+faststart) → content-addresses → indexes and returns the stored `Media`. The `201`/`200` response *is* the confirmation — no scan-polling, no filename matching, idempotent by source hash. TS deletes the staging file once Grout owns the bytes.
- **`+faststart` upstream**: `compose-bumper!` emits `-movflags +faststart` so bumpers arrive stream-ready and Grout can skip a re-mux (it re-adds it regardless).
- **Silent fallback**: if no music tracks available, generate a silent video-only bumper

## Completed ✅

### Tunarr Scheduler (TS)
- [x] Bumper generation pipeline (`bumpers.clj`)
  - Calls Tunabrain `/bumpers` for LLM-generated image (base64 PNG)
  - Selects music track from CC0 library (or silent fallback)
  - Composes image + audio + Ken Burns motion into MP4 via ffmpeg
  - Organizes output into duration buckets (5s/10s/15s)
  - Writes to channel-specific subdirectories on `arr-data` mount
- [x] Tunabrain client (`tunarr/scheduler/tunabrain.clj`) — `generate-bumper!` function
- [x] **Grout client (`tunarr/scheduler/backends/grout/client.clj`)** — `intake!`, `by-hash`, `get-media`, `health-check`, source hashing
- [x] **Batch upload (`upload-bumper-batch!`)** — per-bumper `POST /grout/media`, idempotent by hash, staging cleanup on success
- [x] HTTP API endpoints (`http/api/bumpers.clj`)
  - `GET /api/bumpers` — list staged bumpers
  - `POST /api/bumpers/generate?channel=X&count=N&durations=5,10,15` — trigger generation + Grout upload
- [x] System wiring (`system.clj`, `config.clj`) — Integrant init/halt for `:tunarr/bumpers`, `:grout` config with `GROUT_URL` override
- [x] Container deployment with ffmpeg via `flake.nix` (`pathEnv = [ ffmpeg ]`)
- [x] Shared mount for Grout staging at `GROUT_MEDIA_DIR` (default `/data/media/grout/staging`)
- [x] Removed local `image_generation.clj` — all image generation goes through Tunabrain
- [x] **Removed** the Jellyfin bumper client and PV "Bumpers: <Channel>" collections client — superseded by Grout

### Tunabrain
- [x] Bumper chain (`chains/bumpers.py`) — LLM prompt → OpenRouter Image API → base64 PNG
- [x] `Bumper` / `BumperRequest` models with `image_base64` and `theme`
- [x] `LLMTask.BUMPERS` routing in `llm.py`
- [x] `bumpers_llm_model` in config
- [x] `/bumpers` HTTP endpoint

### Pseudovision (PV)
- [x] `bumper` value added to `event_kind` enum (DB migration + schema)
- [x] Gap filler logic (`scheduling.filler`)
  - `fill-gap-with-bumper` — detects ≤15s gaps, filters by duration bucket, randomly selects
  - `duration-bucket` — rounds duration to nearest 5s bucket
- [x] `apply-filler` in `scheduling.core` — tries bumper injection before regular tail filler
- [x] Streaming overlay (`streaming.manager` / `ffmpeg.hls`)
  - Detects `event_kind="bumper"` in `build-command`
  - Injects ffmpeg `drawtext` with "Coming up next: <title>"
- [x] DB queries (`db.filler`)
  - `find-bumper-collection` — finds collections named `%Bumpers:%`
  - `find-channel-bumper-items` — lists bumper items for a channel
- [x] Playout event kinds include `"bumper"` alongside pre/mid/post/pad/tail/fallback

### Fixes Along the Way
- [x] Fixed `daily_slots.clj` syntax error (unmatched delimiters from debug logging)
- [x] Fixed `group-by` key bug in `pick-item` (`:metadata/media-item-id` → `:item-id`)
- [x] Fixed `ChannelSyncResponse` schema mismatch (TS-1)
- [x] Fixed `get-channel-by-uuid` missing `try/catch` in `delete-test-channel!` (PV-1)
- [x] Fixed `resolve-channel-id` string coercion for `get-channel-by-number` (PV-2)
- [x] Added `GET /api/media/items/{id}/children` endpoint with pagination
- [x] Added `tools/bumper-music/` with `bumper-music-sourcer.py` and `trim-for-bumpers.py`
- [x] Verified Hua Network (130/132) and Enigma TV (173/174) ingestion still working

## In Progress 🚧

- [ ] **End-to-end test** — need to verify:
  1. `POST /api/bumpers/generate?channel=hua&count=1&durations=5` completes
  2. MP4 file is staged under `GROUT_MEDIA_DIR` (e.g. `/data/media/grout/staging/hua/`)
  3. `POST /grout/media` returns `201` (or `200` on a re-run) with the stored `Media`
  4. Staging file is cleaned up; the bumper is queryable via `GET /grout/media?channel=hua&kind=bumper`
  5. PV gap filler queries Grout and injects the bumper into ≤15s gaps
  6. Stream output shows "Coming up next: <title>" overlay on bumper segments

## Blocked / Known Issues 🚫

- **Cannot `kubectl exec` or `kubectl port-forward`** into pods due to RBAC restrictions — need to rely on logs and API calls from outside the cluster
- **`GROUT_URL` secret/env** must be set in the TS deployment (and the Grout mount shared at `GROUT_MEDIA_DIR`) before upload works; unset ⇒ generation still runs and stages files, upload is skipped
- **PV read path**: PV's gap filler must switch from the "Bumpers: <Channel>" collection queries (`db.filler`) to `GET /grout/media` — tracked on the PV side
- **Freesound API key** — `FREESOUND_API_KEY` env var needs to be confirmed in TS deployment secrets before CC0 music sourcing works

## Next Steps 📋

1. **Verify deployment** — check TS pod logs after rollout restart to confirm it starts cleanly with the new Grout client module
2. **Confirm `GROUT_URL`** points at the Grout service and the shared `GROUT_MEDIA_DIR` mount is present
3. **Test generation endpoint** — `POST /api/bumpers/generate?channel=hua&count=1&durations=5`
4. **Verify staging write** — check the mount for a new MP4 under `GROUT_MEDIA_DIR/hua/` (before cleanup)
5. **Verify Grout intake** — check TS logs for a `201`/`200` from `POST /grout/media`, then `GET /grout/media?channel=hua&kind=bumper`
6. **Point PV at Grout** — update PV gap filler to query `GET /grout/media` instead of the old collection
7. **Test gap injection** — check PV scheduling for ≤15s gaps being filled with bumpers
8. **Verify overlay** — check stream output for "Coming up next" text on bumper segments
9. **Source CC0 music** — run `bumper-music-sourcer.py` once `FREESOUND_API_KEY` is confirmed
10. **Add automated batch generation** — cron job or scheduled task to generate N bumpers per channel per day

## Relevant Files

### Tunarr Scheduler
- `src/tunarr/scheduler/bumpers.clj` — Main generation pipeline + Grout upload
- `src/tunarr/scheduler/http/api/bumpers.clj` — HTTP handlers
- `src/tunarr/scheduler/backends/grout/client.clj` — Grout intake/query API client
- `src/tunarr/scheduler/tunabrain.clj` — Tunabrain client
- `src/tunarr/scheduler/system.clj` — Integrant wiring
- `src/tunarr/scheduler/config.clj` — Config resolution (`:grout`, `GROUT_URL`)
- `resources/config.edn` — `:grout` / `:bumpers` config blocks
- `flake.nix` — ffmpeg in container
- `deployment-tunarr-scheduler.yaml` (infra repo) — Grout mount + `GROUT_URL`

### Tunabrain
- `src/tunabrain/chains/bumpers.py` — Bumper chain (LLM + image gen)
- `src/tunabrain/api/models.py` — Bumper models
- `src/tunabrain/api/routes.py` — `/bumpers` endpoint
- `src/tunabrain/llm.py` — Task routing
- `src/tunabrain/config.py` — Model config

### Pseudovision
- `src/pseudovision/scheduling/filler.clj` — Gap filler + bumper selection
- `src/pseudovision/scheduling/core.clj` — `apply-filler` with bumper priority
- `src/pseudovision/streaming/manager.clj` — Bumper detection + overlay text
- `src/pseudovision/ffmpeg/hls.clj` — `drawtext` filter injection
- `src/pseudovision/db/filler.clj` — Bumper collection queries

## Critical Context for Next Session

- TS init sequence: logger → job-runner → tunabrain-throttler → tunabrain → **bumpers** → pseudovision → catalog → curation → http-server
- `GROUT_URL` — Grout base URL (e.g. `http://grout:8080`); unset ⇒ upload disabled. `GROUT_STAGING_DIR`/`BUMPER_OUTPUT_DIR` override the staging dir (default `/data/media/grout/staging`)
- Grout intake body is kebab-case (`path`, `kind`, `channel`, `tags`, `source`, `name`, `description`); responses pin to kebab keys (`duration-ms`, `content-hash`, `stream-url`). Intake is idempotent by SHA-256 of source bytes (`201` new / `200` matched)
- Channel subdirectories under staging: `{GROUT_MEDIA_DIR}/{channel-key}/` (e.g., `hua/`, `enigma/`) — organizational only; Grout re-stores content-addressed
- Duration bucket mapping: ≤7s → 5s, 8-12s → 10s, 13-15s → 15s (generation-side; PV filters on Grout's `duration-ms`)
- ffmpeg `drawtext` requires escaping `\`, `:`, `'`, `%` for filter strings
- The `channel:hua` etc. tags stay in `media_tags`; episodes inherit show tags via `set/union` in `pick-item`
- Batch-level atom tracker in PV for deduplication instead of per-slot DB queries
