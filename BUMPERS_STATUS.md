# Bumper Generation Pipeline — Status & Roadmap

> Last updated: 2026-07-01

## Goal

Implement **LLM-generated channel bumpers** for Pseudovision — short (≤15s) video clips that fill scheduling gaps between programming, with a "Coming up next: <title>" text overlay.

## Architecture

```
┌─────────────────┐     ┌──────────────┐     ┌─────────────┐     ┌──────────────┐
│  Tunarr Scheduler│────▶│  Tunabrain   │────▶│  TS ffmpeg  │────▶│  arr-data    │
│  (orchestrator)  │     │  (LLM + image)│     │  (compose)   │     │  (/data/media)│
└─────────────────┘     └──────────────┘     └─────────────┘     └──────────────┘
         │                                                            │
         │         ┌──────────────┐                                   │
         └────────▶│  Jellyfin    │◀──────────────────────────────────┘
                   │  (indexing)  │
                   └──────────────┘
                          │
                          ▼
                   ┌──────────────┐
                   │ Pseudovision │◀── gap filler injects bumpers
                   │  (playout)   │    with "Coming next" overlay
                   └──────────────┘
```

## Key Design Decisions

- **All AI calls go through Tunabrain** — TS holds no OpenRouter API key
- **Duration buckets**: 5s / 10s / 15s so gaps of varying small sizes can be filled cleanly
- **CC0 music library** via Freesound API (not AI music generation — cheaper, faster, legally cleaner)
- **Shared storage via Jellyfin**: TS writes to `/data/media/bumpers/{channel}/` on `arr-data` (CephFS, RWX), Jellyfin indexes it, PV ingests via normal Jellyfin sync. No direct PVC namespace sharing needed.
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
- [x] Jellyfin client (`tunarr/scheduler/backends/jellyfin/client.clj`) — scan triggers, item lookup
- [x] Pseudovision collections client (`tunarr/scheduler/backends/pseudovision/collections.clj`) — create "Bumpers: <Channel>" collections, add items by Jellyfin ID
- [x] Batch registration (`register-bumper-batch!`) — one Jellyfin scan + polling + PV registration
- [x] HTTP API endpoints (`http/api/bumpers.clj`)
  - `GET /api/bumpers` — list generated bumpers
  - `POST /api/bumpers/generate?channel=X&count=N&durations=5,10,15` — trigger generation
- [x] System wiring (`system.clj`, `config.clj`) — Integrant init/halt for `:tunarr/bumpers`
- [x] Container deployment with ffmpeg via `flake.nix` (`pathEnv = [ ffmpeg ]`)
- [x] `arr-data` PVC mount at `/data/media` in TS deployment
- [x] `JELLYFIN_API_KEY` and `BUMPER_JELLYFIN_LIBRARY` env vars injected from secrets
- [x] Removed local `image_generation.clj` — all image generation goes through Tunabrain

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
  2. MP4 file appears on `arr-data` at `/data/media/bumpers/hua/`
  3. Jellyfin indexes the file (via scan trigger or automatic detection)
  4. TS polling finds the Jellyfin item and registers it in PV "Bumpers: Hua" collection
  5. PV gap filler discovers the bumper and injects it into ≤15s gaps
  6. Stream output shows "Coming up next: <title>" overlay on bumper segments

## Blocked / Known Issues 🚫

- **Cannot `kubectl exec` or `kubectl port-forward`** into pods due to RBAC restrictions — need to rely on logs and API calls from outside the cluster
- **Jellyfin "Bumpers" library** may need manual creation in Jellyfin UI if it doesn't exist yet (TS can trigger scans but can't create libraries)
- **Freesound API key** — `FREESOUND_API_KEY` env var needs to be confirmed in TS deployment secrets before CC0 music sourcing works

## Next Steps 📋

1. **Verify deployment** — check TS pod logs after rollout restart to confirm it starts cleanly with new jellyfin + PV collection modules
2. **Create Jellyfin "Bumpers" library** (if not exists) pointing at `/data/media/bumpers`
3. **Test generation endpoint** — `POST /api/bumpers/generate?channel=hua&count=1&durations=5`
4. **Verify file write** — check `arr-data` for new MP4 in `/data/media/bumpers/hua/`
5. **Verify Jellyfin indexing** — check if item appears in Jellyfin library
6. **Verify PV registration** — check if "Bumpers: Hua" collection gets items
7. **Test gap injection** — check PV scheduling for ≤15s gaps being filled with bumpers
8. **Verify overlay** — check stream output for "Coming up next" text on bumper segments
9. **Source CC0 music** — run `bumper-music-sourcer.py` once `FREESOUND_API_KEY` is confirmed
10. **Add automated batch generation** — cron job or scheduled task to generate N bumpers per channel per day

## Relevant Files

### Tunarr Scheduler
- `src/tunarr/scheduler/bumpers.clj` — Main generation pipeline
- `src/tunarr/scheduler/http/api/bumpers.clj` — HTTP handlers
- `src/tunarr/scheduler/backends/jellyfin/client.clj` — Jellyfin API client
- `src/tunarr/scheduler/backends/pseudovision/collections.clj` — PV collection management
- `src/tunarr/scheduler/tunabrain.clj` — Tunabrain client
- `src/tunarr/scheduler/system.clj` — Integrant wiring
- `src/tunarr/scheduler/config.clj` — Config resolution
- `flake.nix` — ffmpeg in container
- `deployment-tunarr-scheduler.yaml` — `arr-data` PVC mount

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
- `BUMPER_JELLYFIN_LIBRARY` defaults to `"Bumpers"` — Jellyfin library name to scan
- Channel subdirectories: `/data/media/bumpers/{channel-key}/` (e.g., `hua/`, `enigma/`)
- Duration bucket mapping: ≤7s → 5s, 8-12s → 10s, 13-15s → 15s
- ffmpeg `drawtext` requires escaping `\`, `:`, `'`, `%` for filter strings
- The `channel:hua` etc. tags stay in `media_tags`; episodes inherit show tags via `set/union` in `pick-item`
- Batch-level atom tracker in PV for deduplication instead of per-slot DB queries
