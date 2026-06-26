# Dimension-Based Metadata Cleanup — Tracking Document

**Status:** Active cleanup in progress. This document tracks the migration from
hardcoded first-class metadata fields (genres, channels, kid_friendly) to a
unified dimension-based model where all categorization lives in
`media_categorization` and is exported to Pseudovision as prefixed tags.

**Last updated:** 2026-06-25

---

## Core Philosophy

All media metadata is a dimension. A dimension has a name and a set of values.
Instead of privileged fields like `genres` or `kid_friendly`, we have:

- `genre` → `[:action :comedy :sci-fi]`
- `channel` → `[:scifi :toons :prime-time]`
- `age-suitability` → `[:child :teen :any]`
- `time-slot` → `[:morning :daytime :primetime :late-night]`
- `freshness` → `[:classic :retro :modern]`

When exporting to Pseudovision, these become prefixed tags:

```json
["genre:action", "genre:comedy", "channel:scifi", "channel:toons",
 "age-suitability:child", "age-suitability:teen", "time-slot:primetime"]
```

This allows tag-based scheduling in PV without hardcoded concepts.

---

## Fixes Applied

### ✅ Fixed: Ongoing sync ignores dimension categorization

**File:** `src/tunarr/scheduler/media/pseudovision_sync.clj`
**Commit:** `f9e5478`

**Problem:** `sync-item-tags!` derived hardcoded tags from `media.genres`,
`media_channels`, and `media.kid_friendly`, completely ignoring the
`media_categorization` table that `/recategorize` writes to.

**Fix:** Replaced hardcoded derivation with `catalog/get-media-categories`,
flattening all dimension values to `dimension:value` strings before pushing.

---

## Cleanup Methodology

We proceed in two passes before any removal:

### Pass 1: Audit and Mark API Endpoints

For each service (Tunarr Scheduler, Pseudovision, Tunabrain), list every HTTP
endpoint that is part of the public API. Mark as **DEPRECATED** any endpoint
that:

- Returns or accepts hardcoded first-class fields (`genres`, `channels`,
  `kid_friendly`) instead of dimensions or tags
- Encourages the old worldview (e.g., "channel" as a thing, not a dimension
  value)
- Is superseded by a dimension-based or tag-based equivalent

For each deprecated endpoint, document:
- **Why it's deprecated** — the old worldview it enforces
- **What replaces it** — the dimension-aware or tag-based alternative
- **Backwards compatibility plan** — return 410 Gone, or 301 redirect, or keep
  with `Deprecation` header

### Pass 2: Mark Code as Deprecated

Trace backwards from deprecated endpoints to every function, spec, protocol
method, and SQL query that is part of their implementation. Mark each with:

- **`^:deprecated` metadata** in Clojure
- **Docstring note** explaining why and what replaces it
- **Inline comment** linking to the phased cleanup plan

We do **not** delete code in this pass — only mark it so the compiler and
readers know the intent.

---

## Pass 1: API Endpoint Audit

### Tunarr Scheduler

| Route | Status | Old Worldview? | Replacement |
|-------|--------|----------------|-------------|
| `GET /api/genres` | **DEPRECATED** | Lists `genre` as a first-class concept | Use `GET /api/tags` with `tag=genre:*` or query dimensions |
| `GET /api/genres/:genre/media` | **DEPRECATED** | Filters by `genre` as a hardcoded field | Use catalog with dimension filter or tag-based query |
| `GET /api/catalog/channels/:channel-name/media` | **DEPRECATED** | Treats `channel` as a concrete entity | Use `GET /api/media` with `channel:NAME` tag filter |
| `POST /api/media/:library/retag` | **DEPRECATED** | `/tags` endpoint in Tunabrain is obsolete; flat tags are not the dimension model | Use `POST /api/media/:library/recategorize` instead |
| `POST /api/media/:library/recategorize` | ✅ Current | Dimension-based via `/categorize` | — |
| `POST /api/media/:library/sync-pseudovision-tags` | ✅ Current | Pushes all dimensions + tags | — |
| `POST /api/media/tags/audit` | ✅ Current | Tag governance | — |
| `POST /api/media/tags/triage` | ✅ Current | Tag governance | — |
| `POST /api/channels/:channel-id/schedule` | ✅ Current | Layered grid scheduling | — |

### Pseudovision

| Route | Status | Old Worldview? | Replacement |
|-------|--------|----------------|-------------|
| `GET /api/catalog/aggregate` | ✅ Current | Already supports `?tag=` filter | — |
| `CatalogProfile.genres` field | **DEPRECATED** | Hardcoded genre aggregation | Use `tag_aggregates` with `genre:*` prefix |
| `metadata_genres` table | **DEPRECATED** | Hardcoded genre storage | Treat genres as tags; derive `genre:*` tags at ingest |
| `POST /api/media-items/:id/tags` | ✅ Current | Tag storage; no hardcoded concepts | — |

### Tunabrain

| Route | Status | Old Worldview? | Replacement |
|-------|--------|----------------|-------------|
| `POST /tags` | **DEPRECATED** | Flat tag generation; not dimension-aware | Use `POST /categorize` for dimensions, or push dimensions directly |
| `POST /channel-mapping` | **DEPRECATED** | Hardcoded channel mapping | Channels are dimensions; use `POST /categorize` with `channel` dimension |
| `POST /schedule` | **DEPRECATED** | Old batch scheduling | `POST /api/scheduling/propose-quarterly-grid` |
| `POST /categorize` | ✅ Current | Dimension-based categorization | — |
| `POST /api/scheduling/*` | ✅ Current | Layered grid contracts | — |
| `POST /bumpers` | ✅ Current | Bumper generation | — |
| `POST /tag-governance/*` | ✅ Current | Tag audit/triage | — |

---

## Pass 2: Code Deprecation Marks

### Clojure: `^:deprecated` + Docstring

Example pattern:

```clojure
(defn ^:deprecated get-media-by-genre
  "DEPRECATED: Hardcoded genre filter. Genres are dimensions now.
   Use `get-media-by-dimension` or tag-based filtering instead.
   See DIMENSION_CLEANUP.md Phase 3 for removal timeline."
  [catalog genre]
  ...)
```

### Protocol Methods

```clojure
(defprotocol Catalog
  (get-channels [catalog])
  ^:deprecated "DEPRECATED: Hardcoded channel list. Use `get-media-categories` with `channel` dimension."
  ...)
```

### SQL Queries

```clojure
(defn ^:deprecated sql:get-genres
  "DEPRECATED: Queries the `genre` table. Genres are dimensions in `media_categorization` now.
   Use `sql:get-media-categories` or `sql:get-tag-samples` instead."
  []
  (-> (select :name) (from :genre) (order-by :name)))
```

---

## Known Bugs / Regressions

### 🔴 High Priority

#### Pseudovision collection import still sets `::kid-friendly?`
- **File:** `src/tunarr/scheduler/media/pseudovision_collection.clj:52`
- **Issue:** When importing from PV, it sets `::media/kid-friendly? false` on
  every item. This field should be removed entirely; if PV has an age-suitability
  dimension, it should be read as that.
- **Action:** Remove the `assoc` and update the field map to not include
  `kid_friendly`.

---

## Remaining Cruft to Remove

### Database Schema

| Item | Migration | Status | Notes |
|------|-----------|--------|-------|
| `media.kid_friendly` boolean | `20251010-001` | ❌ Still exists | Should be removed or converted to a dimension |
| `genre` table | `20251010-001` | ❌ Still exists | Should be removed; genres are just a dimension |
| `channel` table | `20251010-001` | ❌ Still exists | Should be removed; channels are just a dimension |
| `media_genres` join table | `20251010-001` | ❌ Still exists | Written to by sync; should migrate to `media_categorization` |
| `media_channels` join table | `20251010-001` | ❌ Still exists | Written to by sync; should migrate to `media_categorization` |
| `media_categorization` table | `20251213-001` | ✅ Current | The correct dimension store |
| `media_tags` table | `20251010-001` | ✅ Current | Freeform tags; keep |

### Clojure Specs (`src/tunarr/scheduler/media.clj`)

| Spec | Status | Notes |
|------|--------|-------|
| `::channel-name` | ❌ Obsolete | Hardcoded channel concept |
| `::channel-names` | ❌ Obsolete | Hardcoded channel concept |
| `::channel-descriptions` | ❌ Obsolete | Hardcoded channel concept |
| `::kid-friendly?` | ❌ Obsolete | Hardcoded boolean; should be a dimension |
| `::genre` | ❌ Obsolete | Hardcoded genre concept |
| `::classification` | ❌ Obsolete | Combines `::tags`, `::channel-names`, `::kid-friendly?` |
| `::category-name` | ✅ Current | Dimension name |
| `::category-value` | ✅ Current | Dimension value |

### Catalog Protocol (`src/tunarr/scheduler/media/catalog.clj`)

| Method | Status | Notes |
|--------|--------|-------|
| `get-channels` | ❌ Obsolete | Hardcoded channel concept |
| `get-genres` | ❌ Obsolete | Hardcoded genre concept |
| `get-media-by-channel` | ❌ Obsolete | Hardcoded channel concept |
| `get-media-by-genre` | ❌ Obsolete | Hardcoded genre concept |
| `add-media-channels!` | ❌ Obsolete | Hardcoded channel concept |
| `add-media-genres!` | ❌ Obsolete | Hardcoded genre concept |
| `get-media-categories` | ✅ Current | Dimension read |
| `set-media-category-values!` | ✅ Current | Dimension write |
| `get-media-tags` | ✅ Current | Freeform tags |
| `set-media-tags!` | ✅ Current | Freeform tags |

### SQL Catalog Implementation (`src/tunarr/scheduler/media/sql_catalog.clj`)

| Item | Status | Notes |
|------|--------|-------|
| `::media/channel-names → :channels` | ❌ Obsolete | Field map still maps hardcoded field |
| `::media/genres → :genres` | ❌ Obsolete | Field map still maps hardcoded field |
| `::media/kid-friendly? → :media/kid_friendly` | ❌ Obsolete | Field map still maps hardcoded field |
| `sql:insert-genres` | ❌ Obsolete | Inserts into `genre` table |
| `sql:insert-media-channels` | ❌ Obsolete | Inserts into `media_channels` |
| `sql:insert-media-genres` | ❌ Obsolete | Inserts into `media_genres` |
| `sql:get-genres` | ❌ Obsolete | Queries `genre` table |
| `sql:get-channels` | ❌ Obsolete | Queries `channel` table |
| `sql:add-media-category-values!` | ✅ Current | Inserts into `media_categorization` |
| `sql:get-media-categories` | ✅ Current | Reads from `media_categorization` |

### HTTP API (`src/tunarr/scheduler/http/routes.clj`)

| Route | Status | Notes |
|-------|--------|-------|
| `GET /api/genres` | ❌ Obsolete | Hardcoded genre list |
| `GET /api/genres/:genre/media` | ❌ Obsolete | Hardcoded genre filter |
| `GET /api/catalog/channels/:channel-name/media` | ❌ Obsolete | Hardcoded channel filter |
| `POST /api/media/:library/retag` | ❌ Obsolete | Flat-tag endpoint (via Tunabrain `/tags`) |
| `POST /api/media/:library/recategorize` | ✅ Current | Dimension endpoint (via Tunabrain `/categorize`) |
| `POST /api/media/:library/sync-pseudovision-tags` | ✅ Current | Fixed in commit `f9e5478` |

### Memory Catalog (`src/tunarr/scheduler/media/memory_catalog.clj`)

| Item | Status | Notes |
|------|--------|-------|
| `add-media-genres!` | ❌ Obsolete | Hardcoded genre write |
| `get-media-by-genre` | ❌ Obsolete | Hardcoded genre filter |
| `get-genres` | ❌ Obsolete | Hardcoded genre list |

---

## Tunabrain Cleanup

### Endpoints to Deprecate / Remove

| Endpoint | Status | Used by TS? | Notes |
|----------|--------|-------------|-------|
| `POST /tags` | ❌ Obsolete | ✅ Yes | Old flat-tag generation; TS `retag-media!` calls it |
| `POST /channel-mapping` | ❌ Obsolete | ❌ No | Hardcoded channel mapping |
| `POST /schedule` | ❌ Obsolete | ❌ No | Superseded by `/api/scheduling/*` |
| `POST /categorize` | ✅ Current | ✅ Yes | Dimension-based categorization |
| `POST /bumpers` | ✅ Current | ✅ Yes | Bumper generation |
| `POST /tag-governance/*` | ✅ Current | ✅ Yes | Tag audit/triage |
| `POST /api/scheduling/*` | ✅ Current | ✅ Yes | Layered grid scheduling |

### `MediaItem` Model Fields

| Field | Status | Notes |
|-------|--------|-------|
| `genres` | ❌ Obsolete | Still used as input metadata, but should not be a scheduling output |
| `current_tags` | ✅ Current | Freeform tags |
| `duration_minutes` | ✅ Current | Scalar metadata |
| `rating` | ✅ Current | Scalar metadata (could become a dimension) |

---

## Pseudovision Integration

### Current State

| Feature | Status | Notes |
|---------|--------|-------|
| `GET /api/catalog/aggregate` | ✅ Supports tag filter | `?tag=channel:comedy` works |
| `POST /api/media-items/:id/tags` | ✅ Stores tags | No dimension awareness needed |
| `CatalogProfile.genres` | ❌ Hardcoded | Comes from `metadata_genres` table |
| `CatalogProfile.tag_aggregates` | ✅ Current | Comes from `metadata_tags` |
| `push-daily-slots!` (`category_filters`) | ✅ Current | Uses string tags |

### PV Open Questions

1. Should PV drop `metadata_genres` and treat genres as regular tags?
2. Should PV add a `dimensions` endpoint or keep the tag-based model?
3. Should `CatalogProfile.genres` be deprecated in favor of `tag_aggregates`?

---

## Recommended Phased Cleanup

### Phase 0: Audit + Mark ✅ IN PROGRESS
- [ ] Mark all obsolete API endpoints as DEPRECATED with documentation
- [ ] Mark all obsolete code with `^:deprecated` metadata and docstrings
- [ ] Trace endpoint → handler → service → protocol → SQL for each deprecated
  path

### Phase 1: Fix the sync ✅ DONE
- [x] Update `pseudovision_sync.clj` to read `media_categorization` instead of
  hardcoded fields

### Phase 2: Stop writing to old tables
- [ ] Update `curation.core` to stop writing to `media_genres`, `media_channels`,
  `media.kid_friendly`
- [ ] Update `media.sync` (Jellyfin/PV import) to write dimensions to
  `media_categorization` instead of old tables
- [ ] Update `memory_catalog` to drop genre/channel methods

### Phase 3: Drop the old schema
- [ ] Create migration to drop `media.kid_friendly`, `genre` table, `channel`
  table, `media_genres`, `media_channels`
- [ ] Remove `::genres`, `::channel-names`, `::kid-friendly?` from
  `media.clj`
- [ ] Remove genre/channel endpoints from `http/routes.clj`
- [ ] Remove `get-media-by-genre`, `get-media-by-channel` from catalog
  protocol
- [ ] Remove `sql:insert-genres`, `sql:insert-media-channels`, etc.

### Phase 4: Clean up Tunabrain
- [ ] Update TS `retag-media!` to use `/categorize` instead of `/tags`
- [ ] Remove `/tags`, `/channel-mapping`, `/schedule` endpoints from Tunabrain
- [ ] Remove `genres` from `MediaItem` scheduling output (keep as input metadata)
- [ ] Update prompts to use dimensions instead of flat tags

---

## Cross-References

- `SCHEDULING.md` — Layered grid scheduling design
- `PSEUDOVISION_SYNC.md` — Tag sync documentation (needs update)
- `PSEUDOVISION_MIGRATION.md` — Migration guide (reflects correct approach)
- Tunabrain `docs/scheduling-grid-spec.md` — Authoritative wire contracts
- Tunabrain `src/tunabrain/api/models.py` — Pydantic models
