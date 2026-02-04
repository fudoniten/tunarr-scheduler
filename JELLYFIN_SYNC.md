# Jellyfin Tag Sync

This document describes the Jellyfin tag synchronization feature that allows tunarr-scheduler to push tags to Jellyfin media items.

## Overview

The Jellyfin sync endpoint enables you to:
1. Manage media curation and tagging in tunarr-scheduler
2. Automatically push those tags to Jellyfin via API
3. Have ErsatzTV sync the updated tags from Jellyfin
4. Use ErsatzTV smart collections based on those tags for scheduling

## Workflow

```
tunarr-scheduler (tag management)
  ↓ (sync tags via API)
Jellyfin (media server)
  ↓ (library sync)
ErsatzTV (pulls metadata including tags)
  ↓ (smart collections)
Scheduled programming
```

## API Endpoints

### List Libraries

**Endpoint:** `GET /api/media/libraries`

**Description:** Returns all configured libraries with their IDs.

**Example:**
```bash
curl http://localhost:8080/api/media/libraries
```

**Response:**
```json
{
  "libraries": {
    "movies": {
      "name": "movies",
      "id": "abc123def456"
    },
    "tv": {
      "name": "tv",
      "id": "def789ghi012"
    }
  }
}
```

### Sync Library Tags to Jellyfin

**Endpoint:** `POST /api/media/:library/sync-jellyfin-tags`

**Description:** Syncs all tags from the catalog to Jellyfin for the specified library. This operation:
- Iterates over all media items in the specified library
- Retrieves current tags from the catalog for each item
- Updates the corresponding Jellyfin item via the Jellyfin API (`POST /Items/{itemId}`)
- **Replaces** all existing tags on the Jellyfin item with the catalog's tags

**Parameters:**
- `library` (path parameter): The library name/ID to sync (e.g., "movies", "tv")

**Response:**
- `202 Accepted`: Job submitted successfully
- Returns a job ID that can be used to check status

**Example:**
```bash
curl -X POST http://localhost:8080/api/media/movies/sync-jellyfin-tags
```

**Response:**
```json
{
  "job": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "type": "media/jellyfin-sync",
    "status": "pending",
    "metadata": {
      "library": "movies"
    }
  }
}
```

### Check Job Status

**Endpoint:** `GET /api/jobs/:job-id`

**Example:**
```bash
curl http://localhost:8080/api/jobs/550e8400-e29b-41d4-a716-446655440000
```

**Response:**
```json
{
  "job": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "type": "media/jellyfin-sync",
    "status": "completed",
    "metadata": {
      "library": "movies"
    },
    "progress": {
      "phase": "syncing",
      "current": 150,
      "total": 150
    },
    "result": {
      "synced": 148,
      "failed": 2,
      "errors": [
        {
          "item-id": "abc123",
          "error": "Item not found"
        }
      ]
    }
  }
}
```

## Configuration

The Jellyfin sync feature uses the same configuration as your media collection source. Make sure your `config.edn` includes:

```clojure
{:collection
 {:type :jellyfin
  :api-key "your-jellyfin-api-key"
  :base-url "http://jellyfin.example.com:8096"
  :libraries {:movies "library-id-1"
              :tv "library-id-2"}}}
```

Environment variables can also be used:
- `COLLECTION_API_KEY`: Jellyfin API key
- `COLLECTION_BASE_URL`: Jellyfin server URL

## Tag Format

Tags in tunarr-scheduler are stored as keywords (e.g., `:sci-fi`, `:action-adventure`). When syncing to Jellyfin, they are converted to PascalCase strings:
- `:sci-fi` → `SciFi`
- `:action-adventure` → `ActionAdventure`  
- `:daytime-mystery` → `DaytimeMystery`

## Usage Tips

1. **Initial Sync**: After tagging media in tunarr-scheduler, run a sync to Jellyfin
2. **ErsatzTV Sync**: Trigger a library scan in ErsatzTV to pull the updated tags from Jellyfin
3. **Create Smart Collections**: In ErsatzTV, create smart collections using tag queries
4. **Schedule**: Use the ErsatzTV Scripted Schedule API to schedule your smart collections

## ErsatzTV Smart Collections

After syncing tags to Jellyfin, you can create ErsatzTV smart collections:

```bash
# Create a smart collection for sci-fi movies
curl -X POST http://ersatztv:8409/api/collections/smart/new \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Sci-Fi Movies",
    "query": "tag:SciFi AND type:movie"
  }'
```

Then schedule it in your ErsatzTV scripted schedule:

```bash
curl -X POST http://ersatztv:8409/api/scripted/playout/build/{buildId}/add_smart_collection \
  -H "Content-Type: application/json" \
  -d '{
    "key": "scifi",
    "smartCollection": "Sci-Fi Movies",
    "order": "shuffle"
  }'
```

## Troubleshooting

### Tags Not Appearing in ErsatzTV

1. Verify tags were synced to Jellyfin by checking the media item in Jellyfin's web UI
2. Trigger a library scan in ErsatzTV
3. Check ErsatzTV logs for sync errors

### Sync Job Failing

Check the job status for error details:
```bash
curl http://localhost:8080/api/jobs/{job-id}
```

Common issues:
- **Invalid API key**: Verify `COLLECTION_API_KEY` or `:api-key` in config
- **Jellyfin unreachable**: Check `COLLECTION_BASE_URL` or `:base-url`
- **Item not found**: The media item ID in catalog doesn't match Jellyfin

### Known Issues

There was a bug in Jellyfin (issue #10724) related to updating tags via API that could corrupt items. This was in older versions. Ensure you're running a recent version of Jellyfin.

## Implementation Details

**Files:**
- `src/tunarr/scheduler/media/jellyfin_sync.clj`: Core sync logic
- `src/tunarr/scheduler/http/routes.clj`: API endpoint definition
- `src/tunarr/scheduler/system.clj`: Dependency injection
- `src/tunarr/scheduler/config.clj`: Configuration loading

**Jellyfin API:**
- Endpoint: `POST /Items/{itemId}`
- Authentication: `X-Emby-Token` header
- Request body: `BaseItemDto` with `Tags` field
- Response: `204 No Content` on success
