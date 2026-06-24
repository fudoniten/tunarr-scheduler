# Pseudovision Migration Guide

This document describes the one-time migration process to push existing LLM metadata from tunarr-scheduler's local catalog to Pseudovision.

## Overview

**Why migrate?**
- Preserve your existing LLM categorization work
- Switch to Pseudovision as the single source of truth
- Eliminate dual-source synchronization issues
- Simplify the architecture going forward

**What gets migrated?**
- Tags (from `media_tags` table)
- Categories (from `media_categorization` table) → converted to namespaced tags
- All metadata is matched via Jellyfin IDs

## API Endpoint

```
POST /api/media/migrate-to-pseudovision
Content-Type: application/json
```

### Request Body

```json
{
  "dry-run": true,               // Optional: test without making changes (default: false)
  "include-categories": true,     // Optional: convert categories to tags (default: true)
  "batch-size": 10,              // Optional: items per batch (default: 10)
  "delay-ms": 100                // Optional: delay between items in ms (default: 100)
}
```

### Response

```json
{
  "total": 500,          // Total items processed
  "success": 450,        // Successfully migrated
  "not-found": 30,       // Items not in Pseudovision
  "skipped": 15,         // Items with no tags/categories
  "errors": 5,           // Failed to migrate
  "dry-run": 0,          // Dry run count (if dry-run: true)
  "message": "Migration complete"
}
```

## Migration Workflow

### Step 1: Dry Run (RECOMMENDED)

Test the migration without making changes:

```bash
curl -X POST http://localhost:8080/api/media/migrate-to-pseudovision \
  -H "Content-Type: application/json" \
  -d '{
    "dry-run": true,
    "batch-size": 5
  }'
```

**Check the logs** to see:
- Which items will be migrated
- What tags will be added
- Any items not found in Pseudovision

### Step 2: Run Migration

Once you're confident, run the real migration:

```bash
curl -X POST http://localhost:8080/api/media/migrate-to-pseudovision \
  -H "Content-Type: application/json" \
  -d '{
    "dry-run": false,
    "include-categories": true,
    "batch-size": 10,
    "delay-ms": 100
  }'
```

**Monitor progress:**
- Watch the server logs for batch completion updates
- Response returns when migration is complete
- May take several minutes depending on catalog size

### Step 3: Validate (Optional)

Check a few items in Pseudovision to verify tags were added:

```bash
# Get tags for a media item in Pseudovision
curl http://pseudovision.kube.sea.fudo.link/api/media-items/{item-id}/tags
```

## Category to Tag Conversion

Categories are flattened into namespaced tags:

**Example:**

Local catalog categories:
```clojure
{:channel [:comedy :sitcom-spectrum]
 :time-slot [:primetime :late-night]
 :audience [:adult]
 :season [:summer]
 :freshness [:modern]}
```

Converted to Pseudovision tags:
```
["channel:comedy", "channel:sitcom-spectrum", 
 "time-slot:primetime", "time-slot:late-night",
 "audience:adult", "season:summer", "freshness:modern"]
```

Plus any existing tags from `media_tags` table.

## What Happens During Migration

For each media item in your catalog:

1. **Query local catalog** - Get media ID (Jellyfin ID), tags, categories
2. **Find in Pseudovision** - Look up by `remote-key` (Jellyfin ID)
3. **Build tag list** - Combine tags + flattened categories
4. **Push to Pseudovision** - `POST /api/media-items/{id}/tags`
5. **Log result** - Success, not-found, or error

## Handling Edge Cases

### Items Not Found in Pseudovision
- **Cause**: Item exists in Jellyfin/catalog but not synced to Pseudovision yet
- **Action**: Run Pseudovision library scan first: `POST /api/media/libraries/{id}/scan`
- **Status**: Counted as `not-found` in migration results

### Items With No Tags/Categories
- **Status**: Counted as `skipped`
- **Action**: No API call made (nothing to migrate)

### API Errors
- **Status**: Counted as `errors`
- **Logged**: Full error details in server logs
- **Migration**: Continues to next item (non-blocking)

## Rate Limiting

The migration includes built-in rate limiting:

- `batch-size`: Process N items, then log progress
- `delay-ms`: Wait between each item to avoid overwhelming Pseudovision API

**Recommended settings:**
- Small catalog (<100 items): `batch-size: 20, delay-ms: 50`
- Medium catalog (100-1000): `batch-size: 10, delay-ms: 100` (default)
- Large catalog (1000+): `batch-size: 5, delay-ms: 200`

## After Migration

Once migration is complete:

1. **Verify tags in Pseudovision**: Check a few items manually
2. **Test schedule generation**: Create a schedule using migrated tags
3. **Switch to Pseudovision workflow**: Stop using local catalog for new tagging

### New Workflow (Post-Migration)

```bash
# Query media from Pseudovision (not local catalog)
GET /api/media/libraries/{library-id}/items

# LLM categorize and tag directly to Pseudovision
# (new implementation - not yet built)

# Generate schedules using Pseudovision tags
POST /api/channels/{channel-id}/schedule
{
  "slots": [{
    "required-tags": ["comedy", "channel:sitcom-spectrum"],
    "time": "20:00:00"
  }]
}
```

## Idempotency

The migration is **safe to re-run**:
- Pseudovision's `POST /api/media-items/{id}/tags` endpoint adds tags (doesn't replace)
- If interrupted, you can re-run and it will skip already-tagged items
- Use dry-run mode to see what would change on a re-run

## Troubleshooting

### "Item not found in Pseudovision"
```bash
# Solution: Scan the library in Pseudovision first
curl -X POST http://pseudovision.kube.sea.fudo.link/api/media/libraries/{library-id}/scan

# Wait for scan to complete, then re-run migration
```

### Migration very slow
```bash
# Solution: Increase batch size, decrease delay
{
  "batch-size": 20,
  "delay-ms": 50
}
```

### Duplicate tags after migration
- Pseudovision deduplicates automatically
- Safe to ignore or manually clean up via Pseudovision UI

## Example: Full Migration Flow

```bash
# 1. Test with dry run
curl -X POST http://localhost:8080/api/media/migrate-to-pseudovision \
  -H "Content-Type: application/json" \
  -d '{"dry-run": true}'

# Check logs, verify looks good

# 2. Run real migration
curl -X POST http://localhost:8080/api/media/migrate-to-pseudovision \
  -H "Content-Type: application/json" \
  -d '{"dry-run": false, "batch-size": 10, "delay-ms": 100}'

# Response:
# {
#   "total": 487,
#   "success": 450,
#   "not-found": 25,
#   "skipped": 10,
#   "errors": 2,
#   "message": "Migration complete"
# }

# 3. Verify in Pseudovision
curl http://pseudovision.kube.sea.fudo.link/api/tags | jq

# 4. Test schedule with migrated tags
curl -X POST http://localhost:8080/api/channels/2/schedule \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Test Schedule",
    "slots": [{
      "time": "18:00:00",
      "duration_hours": 2,
      "required_tags": ["comedy", "channel:sitcom-spectrum"],
      "playback_order": "shuffle"
    }],
    "horizon": 7
  }'
```

## Next Steps

After successful migration, consider:

1. **Implement new LLM workflow** - Query Pseudovision, categorize, tag directly
2. **Deprecate local catalog** - Mark as optional/analytics-only
3. **Create schedule templates** - Leverage migrated category tags
4. **Set up automation** - Daily schedule regeneration using Pseudovision data

See `ROADMAP.md` for the complete roadmap.
