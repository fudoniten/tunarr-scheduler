# Bumper Generation Pipeline — Status & Roadmap

> Last updated: 2026-07-26

## Goal

Implement **LLM-generated channel bumpers** for Pseudovision — short (≤15s) video clips that fill scheduling gaps between programming, with a "Coming up next: <title>" text overlay.

## Architecture

Bumpers now flow into **Grout**, the filler-content store, via its intake API —
replacing the old Jellyfin scan → poll → PV-collection registration path. TS
and Grout share no filesystem: the composed MP4 is staged in a local scratch
dir on the TS pod and its bytes are streamed to Grout over a multipart HTTP
upload.

```
┌─────────────────┐     ┌──────────────┐     ┌─────────────┐     ┌──────────────────┐
│  Tunarr Scheduler│────▶│  Tunabrain   │────▶│  TS ffmpeg  │────▶│ TS local scratch │
│  (orchestrator)  │     │  (LLM + image)│     │  (compose)   │     │  dir (this pod)  │
└─────────────────┘     └──────────────┘     └─────────────┘     └──────────────────┘
         │                                                            │
         │  POST /grout/media (multipart: file bytes, kind=bumper,   │
         │  channel, tags, …) — no shared filesystem required        │
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
- **Storage via Grout** (was Jellyfin): TS composes to a local scratch dir, then `POST /grout/media` streams the file's bytes as a `multipart/form-data` upload (no shared filesystem with Grout). Grout hashes → ffprobes → normalises (+faststart) → content-addresses → indexes and returns the stored `Media`. The `201`/`200` response *is* the confirmation — no scan-polling, no filename matching, idempotent by source hash. TS deletes the local scratch file once Grout owns the bytes.
- **`+faststart` upstream**: `compose-bumper!` emits `-movflags +faststart` so bumpers arrive stream-ready and Grout can skip a re-mux (it re-adds it regardless).
- **Silent fallback**: if no music tracks available, generate a silent video-only bumper

## Completed ✅

### Tunarr Scheduler (TS)
- [x] Bumper generation pipeline (`bumpers.clj`)
  - Calls Tunabrain `/bumpers` for LLM-generated image (base64 PNG)
  - Selects music track from CC0 library (or silent fallback)
  - Composes image + audio + Ken Burns motion into MP4 via ffmpeg
  - Organizes output into duration buckets (5s/10s/15s)
  - Writes to channel-specific subdirectories in a local scratch dir
- [x] Tunabrain client (`tunarr/scheduler/tunabrain.clj`) — `generate-bumper!` function
- [x] **Grout client (`tunarr/scheduler/backends/grout/client.clj`)** — `intake!` (multipart upload), `by-hash`, `get-media`, `health-check`, source hashing
- [x] **Batch upload (`upload-bumper-batch!`)** — per-bumper `POST /grout/media` multipart upload, idempotent by hash, scratch-file cleanup on success
- [x] HTTP API endpoints (`http/api/bumpers.clj`)
  - `GET /api/bumpers` — list staged bumpers
  - `POST /api/bumpers/generate?channel=X&count=N&durations=5,10,15` — trigger generation + Grout upload
- [x] System wiring (`system.clj`, `config.clj`) — Integrant init/halt for `:tunarr/bumpers`, `:grout` config with `GROUT_URL` override
- [x] Container deployment with ffmpeg via `flake.nix` (`pathEnv = [ ffmpeg ]`)
- [x] Local scratch dir for pre-upload staging (`BUMPER_OUTPUT_DIR`, default a `tunarr-bumpers` subdir under the JVM temp dir) — **no longer shared with Grout**; see "Shared-filesystem cleanup" below
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
  2. MP4 file is staged under the local scratch dir (e.g. `${BUMPER_OUTPUT_DIR}/hua/`)
  3. `POST /grout/media` (multipart upload) returns `201` (or `200` on a re-run) with the stored `Media`
  4. Scratch file is cleaned up; the bumper is queryable via `GET /grout/media?channel=hua&kind=bumper`
  5. PV gap filler queries Grout and injects the bumper into ≤15s gaps
  6. Stream output shows "Coming up next: <title>" overlay on bumper segments

## Shared-filesystem cleanup 🧹

TS and Grout no longer need to share a filesystem for the bumper write path —
`grout/client.clj`'s `intake!` streams the file's bytes as a multipart POST
(matching Grout's `POST /grout/media` contract, which itself dropped
path-based intake). `BUMPER_OUTPUT_DIR` is now a purely-local scratch dir; the
old `GROUT_STAGING_DIR`/`GROUT_MEDIA_DIR` env vars and the `/data/media/grout/...`
default path are gone from this repo. The one remaining piece: the external
infra repo's `deployment-tunarr-scheduler.yaml` still mounts a shared volume
for TS solely to satisfy the old assumption — that mount can be dropped once
this ships (tracked outside this repo).

## Blocked / Known Issues 🚫

- **Cannot `kubectl exec` or `kubectl port-forward`** into pods due to RBAC restrictions — need to rely on logs and API calls from outside the cluster
- **`GROUT_URL` secret/env** must be set in the TS deployment before upload works; unset ⇒ generation still runs and stages files locally, upload is skipped. No shared mount is required.
- **PV read path**: PV's gap filler must switch from the "Bumpers: <Channel>" collection queries (`db.filler`) to `GET /grout/media` — tracked on the PV side
- **Freesound API key** — `FREESOUND_API_KEY` env var needs to be confirmed in TS deployment secrets before CC0 music sourcing works

## Next Steps 📋

1. **Verify deployment** — check TS pod logs after rollout restart to confirm it starts cleanly with the multipart-upload Grout client
2. **Confirm `GROUT_URL`** points at the Grout service (no shared mount needed)
3. **Test generation endpoint** — `POST /api/bumpers/generate?channel=hua&count=1&durations=5`
4. **Verify local scratch write** — check the TS pod's own scratch dir for a new MP4 under `${BUMPER_OUTPUT_DIR}/hua/` (before cleanup)
5. **Verify Grout intake** — check TS logs for a `201`/`200` from `POST /grout/media`, then `GET /grout/media?channel=hua&kind=bumper`
6. **Point PV at Grout** — update PV gap filler to query `GET /grout/media` instead of the old collection
7. **Test gap injection** — check PV scheduling for ≤15s gaps being filled with bumpers
8. **Verify overlay** — check stream output for "Coming up next" text on bumper segments
9. **Source CC0 music** — run `bumper-music-sourcer.py` once `FREESOUND_API_KEY` is confirmed
10. **Add automated batch generation** — cron job or scheduled task to generate N bumpers per channel per day
11. **Drop the shared volume mount** for TS in `deployment-tunarr-scheduler.yaml` (infra repo) — no longer needed for bumper upload

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
- `deployment-tunarr-scheduler.yaml` (infra repo) — `GROUT_URL`; the shared volume mount here can be dropped now that upload is HTTP-only

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
- `GROUT_URL` — Grout base URL (e.g. `http://grout:8080`); unset ⇒ upload disabled. `BUMPER_OUTPUT_DIR` overrides the local scratch dir (default: a `tunarr-bumpers` subdir under the JVM temp dir) — this dir only needs to be local to the TS pod, not shared with Grout
- Grout intake (`POST /grout/media`) is a `multipart/form-data` upload: `file` carries the bytes, `kind`/`channel`/`tags` (comma-joined string)/`source`/`source-url`/`name`/`description` are plain string fields. Responses pin to kebab keys (`duration-ms`, `content-hash`, `stream-url`). Intake is idempotent by SHA-256 of source bytes (`201` new / `200` matched)
- Channel subdirectories under the local scratch dir: `{BUMPER_OUTPUT_DIR}/{channel-key}/` (e.g., `hua/`, `enigma/`) — organizational only; Grout re-stores content-addressed
- Duration bucket mapping: ≤7s → 5s, 8-12s → 10s, 13-15s → 15s (generation-side; PV filters on Grout's `duration-ms`)
- ffmpeg `drawtext` requires escaping `\`, `:`, `'`, `%` for filter strings
- The `channel:hua` etc. tags stay in `media_tags`; episodes inherit show tags via `set/union` in `pick-item`
- Batch-level atom tracker in PV for deduplication instead of per-slot DB queries
