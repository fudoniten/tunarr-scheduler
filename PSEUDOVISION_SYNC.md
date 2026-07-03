# Pseudovision Tag Sync

This document describes the Pseudovision tag synchronization feature that allows tunarr-scheduler to push curated tags to Pseudovision media items.

## Overview

The Pseudovision sync endpoint enables you to:
1. Manage media curation and tagging in tunarr-scheduler
2. Automatically push those tags to Pseudovision via API
3. Use tag-based slot filtering in Pseudovision schedules
4. Leverage native scheduling engine for playout

## Workflow

```
tunarr-scheduler (tag management + LLM curation)
  ↓ (sync tags via API)
Pseudovision (tag storage + scheduling engine + streaming)
  ↓ (tag-based filtering)
Scheduled programming
```

## API Endpoints

### List Libraries

**Endpoint:** `GET /api/media/libraries`

**Description:** Returns all configured libraries with their IDs.

**Example:**
```bash
curl http://localhost:5545/api/media/libraries
```

**Response:**
```json
{
  {
    :libraries {
      :movies {:name \"movies\", :id \"abc123def456\"}
      :shows {:name \"shows\", :id \"def789ghi012\"}
    }
  }
}
```

### Sync Library Tags to Pseudovision

**Endpoint:** `POST /api/media/:library/sync-pseudovision-tags`

**Description:** Syncs all tags from the catalog to Pseudovision for the specified library. This operation:
- Iterates over all media items in the specified library
- Retrieves current tags from the catalog for each item
- Updates the corresponding Pseudovision item via the Pseudovision API
- Maps via Jellyfin `remote_key` to find matching media items

**Parameters:**
- `library` (path parameter): The library name/ID to sync (e.g., `movies`, `shows`)

**Response:**
- `202 Accepted`: Job submitted successfully
- Returns a job ID that can be used to check status

**Example:**
```bash
curl -X POST http://localhost:5545/api/media/movies/sync-pseudovision-tags
```

**Response:**
```json
{
  {
    :job {
      :id \"550e8400-e29b-41d4-a716-446655440000\"
      :type \"media/pseudovision-sync\"
      :status \"pending\"
      :metadata {:library \"movies\"}
    }
  }
}
```

### Check Job Status

**Endpoint:** `GET /api/jobs/:job-id`

**Example:**
```bash
curl http://localhost:5545/api/jobs/550e8400-e29b-41d4-a716-446655440000
```

**Response:**
```json
{
  {
    :job {
      :id \"550e8400-e29b-41d4-a716-446655440000\"
      :type \"media/pseudovision-sync\"
      :status \"completed\"
      :metadata {:library \"movies\"}
      :progress {:phase \"syncing\", :current 150, :total 150}
      :result {:synced 148, :failed 2, :errors [{:item-id \"abc123\", :error \"Item not found in Pseudovision\"}]}
    }
  }
}
```

## Configuration

The Pseudovision sync feature uses the configuration in `config.edn`:

```clojure
{:pseudovision {:base-url #or [#env PSEUDOVISION_URL \"http://localhost:8080\"]
                :auto-sync true}}
```

Environment variables:
- `PSEUDOVISION_URL`: Pseudovision server URL

## Automatic Sync (Event-Driven)

You don't have to trigger syncs manually or on a cron. When `:auto-sync` is
enabled (the default) and Pseudovision is configured, tag and category edits
are pushed to Pseudovision **as they happen**.

Tunarr Scheduler is the source of truth for tags, and every tag/category write
funnels through the catalog. In auto-sync mode the catalog is wrapped with a
decorator that, on each item-level tag/category mutation, marks that item
"dirty". A background worker coalesces dirty items over a short debounce window
and syncs each once — so an LLM retagging a whole library collapses into a
single batched pass rather than hundreds of blocking calls. Global tag
operations (rename/delete/purge across all items) can't name the items they
touch, so they trigger a full reconcile instead.

A low-frequency periodic reconcile runs as a backstop for changes that never
flowed through the process (e.g. direct DB edits) or that were dropped during a
Pseudovision outage.

This complements — and can replace — the manual `sync-pseudovision-tags`
endpoint, which remains available for on-demand full-library syncs.

### Tuning

```clojure
{:pseudovision {:auto-sync true            ; master switch (also gates channel startup sync)
                :sync-debounce-ms 3000      ; coalescing window after the first edit
                :sync-bulk-threshold 50     ; build the id-map once above this batch size
                :sync-backoff-ms 30000      ; pause before retrying a failed batch
                :sync-reconcile-hours 24}}  ; periodic full-reconcile backstop; 0 disables
```

Environment overrides: `PSEUDOVISION_AUTO_SYNC`, `PSEUDOVISION_SYNC_DEBOUNCE_MS`,
`PSEUDOVISION_SYNC_BULK_THRESHOLD`, `PSEUDOVISION_SYNC_BACKOFF_MS`,
`PSEUDOVISION_SYNC_RECONCILE_HOURS`.

Set `:auto-sync false` (or `PSEUDOVISION_AUTO_SYNC=false`) to fall back to
manual/cron syncing; the catalog is then used unwrapped with no worker.

## Tag Format

Tags in tunarr-scheduler are stored as keywords (e.g., `:sci-fi`, `:action-adventure`). When syncing to Pseudovision, they are converted to strings with hyphens preserved:
- `:sci-fi` → `sci-fi`
- `:action-adventure` → `action-adventure`
- `:daytime-mystery` → `daytime-mystery`

## Tag Categories

Tags are organized by category dimension:

| Dimension | Example Tags |
|-----------|--------------|
| `channel` | `spectrum`, `enigma`, `galaxy`, `nippon` |
| `time-slot` | `morning`, `daytime`, `primetime`, `late-night` |
| `audience` | `kids`, `family`, `teen`, `adult` |
| `season` | `spring`, `summer`, `fall`, `winter`, `christmas`, `halloween` |
| `freshness` | `classic`, `retro`, `modern`, `contemporary` |

## Usage Tips

1. **Initial Sync**: After tagging media in tunarr-scheduler, run a sync to Pseudovision
2. **Create Schedules**: Use tag filters in schedule slots (`required_tags`, `excluded_tags`)
3. **Verify**: Check tags in Pseudovision API: `GET /api/tags`

## Pseudovision Tag Filtering

After syncing tags to Pseudovision, you can use them in schedule slots:

```bash
# Create a schedule with tag-based filtering
curl -X POST http://pseudovision:8080/api/schedules/42/slots -d '{
  slot_index: 0,
  anchor: \"fixed\",
  start_time: \"20:00:00\",
  fill_mode: \"flood\",
  required_tags: [\"comedy\", \"sitcom\"],
  excluded_tags: [\"explicit\", \"nsfw\"],
  playback_order: \"shuffle\"
}'
```

### Tag Filtering Logic

- `required_tags`: Item must have ALL specified tags (AND)
- `excluded_tags`: Item must NOT have ANY specified tags (NOT)

Example: `required_tags: ['comedy', 'sitcom'], excluded_tags: ['explicit']`
- Matches: Comedy sitcoms without explicit content
- Excludes: Pure dramas, or anything marked explicit

## Troubleshooting

### Tags Not Appearing in Pseudovision

1. Verify tags were synced to Pseudovision: `GET /api/tags`
2. Check job status for errors
3. Ensure media items exist in Pseudovision (run library scan first)

### Sync Job Failing

Check the job status for error details:
```bash
curl http://localhost:5545/api/jobs/{job-id}
```

Common issues:
- **Pseudovision unreachable**: Check `PSEUDOVISION_URL` configuration
- **Item not found**: Media item in catalog not yet in Pseudovision (run scan first)
- **API errors**: Check Pseudovision logs

### Known Issues

- Jellyfin-sidekick tag updates may fail with HTTP 500 errors. This affects the indirect sync path but not direct Pseudovision sync.

## Implementation Details

**Files:**
- `src/tunarr/scheduler/media/pseudovision_sync.clj`: Core sync logic (per-item, batch, and full-library)
- `src/tunarr/scheduler/media/pseudovision_autosync.clj`: Event-driven auto-sync — catalog decorator + debounced worker
- `src/tunarr/scheduler/backends/pseudovision/client.clj`: HTTP client
- `src/tunarr/scheduler/http/routes.clj`: API endpoint definition
- `src/tunarr/scheduler/system.clj`: Dependency injection (`:tunarr/raw-catalog` + wrapping `:tunarr/catalog`)
- `src/tunarr/scheduler/config.clj`: Configuration loading

**Pseudovision API:**
- Endpoint: `POST /api/media-items/{itemId}/tags`
- Authentication: Bearer token (if configured)
- Request body: `{\"tags\": [\"tag1\", \"tag2\", ...]}`

## Migration from Jellyfin Sync

If you previously used Jellyfin tag sync (for ErsatzTV), the new workflow is:

**Before:**
```bash
# Sync to Jellyfin (3-hop)
curl -X POST http://localhost:5545/api/media/movies/sync-jellyfin-tags
# Then ErsatzTV pulls from Jellyfin
```

**After:**
```bash
# Sync directly to Pseudovision (1-hop)
curl -X POST http://localhost:5545/api/media/movies/sync-pseudovision-tags
# Pseudovision handles everything
```

See `PSEUDOVISION_MIGRATION.md` for one-time migration of existing tags.