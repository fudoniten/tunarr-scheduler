# Performance Analysis Report - Tunarr Scheduler

**Analysis Date:** 2026-01-08
**Codebase:** tunarr-scheduler (Clojure media scheduling service)

## Executive Summary

This analysis identified **7 critical performance anti-patterns** that could significantly impact performance at scale. The most severe issues involve N+1 queries, eager realization of large datasets, unbounded database queries, and blocking I/O operations in loops.

---

## Critical Issues (High Priority)

### 1. ❌ N+1 Query Pattern in Media Sync
**Location:** `src/tunarr/scheduler/media/sync.clj:26-31`
**Severity:** CRITICAL

```clojure
(doseq [[n item] items]
  (log/info (format "adding media item: %s" (::media/name item)))
  (catalog/add-media! catalog item)  ; ← Individual DB call per item!
  (report-progress {...}))
```

**Problem:**
- Each media item triggers a separate database transaction
- For a library with 10,000 items, this creates 10,000+ individual database operations
- The `add-media!` function calls `sql:exec-with-tx!` which wraps each insert in its own transaction

**Impact:**
- Linear scaling: O(n) database round-trips
- Network latency multiplied by item count
- Transaction overhead for each item
- Estimated 100x-1000x slower than batch operations

**Solution:**
Implement batch insert operations:
```clojure
;; Group items into batches of 100-500
(doseq [batch (partition-all 500 items)]
  (catalog/add-media-batch! catalog batch))
```

---

### 2. ❌ Eager Realization of Entire Collection
**Location:** `src/tunarr/scheduler/media/sync.clj:24-25`
**Severity:** CRITICAL

```clojure
(let [items (map-indexed vector (collection/get-library-items collection library))
      total-items (count items)]  ; ← Forces full realization!
```

**Problem:**
- `map-indexed vector` combined with `count` forces the entire collection into memory
- All API results are fetched and stored before processing begins
- No streaming or lazy evaluation

**Impact:**
- Memory usage: O(n) where n = total library items
- For 50,000 media items @ ~5KB each = 250MB+ held in memory
- Increased GC pressure
- Cannot start processing until all items are fetched

**Solution:**
Use chunked processing with progress tracking:
```clojure
(let [items (collection/get-library-items collection library)
      ;; Use volatile for progress counter instead of count
      processed (volatile! 0)]
  (doseq [item items]
    (catalog/add-media! catalog item)
    (vswap! processed inc)
    (report-progress {:library library
                      :complete-items @processed})))
```

---

### 3. ❌ Unbounded Database Query (No Pagination)
**Location:** `src/tunarr/scheduler/media/sql_catalog.clj:311-341`
**Severity:** HIGH

```clojure
(defn sql:get-media []
  (-> (select :media.id :media.name ...)
      (from :media)
      (left-join :media_tags ...)
      (left-join :media_channels ...)
      (left-join :media_genres ...)
      (left-join :media_taglines ...)
      (group-by :media.id)))
```

**Problem:**
- No LIMIT or OFFSET clauses
- Fetches ALL media items in a single query
- Multiple LEFT JOINs with array aggregation operations
- Used by `get-media` method (line 348-349)

**Impact:**
- Query time grows quadratically with joins and aggregations
- Memory consumption for large result sets
- Database load spikes
- For 100,000+ media items: multi-second query times, 100s of MB in memory

**Solution:**
Add pagination support:
```clojure
(defn sql:get-media [& {:keys [limit offset]}]
  (cond-> (-> (select ...)
              (from :media)
              ...)
    limit (sql/limit limit)
    offset (sql/offset offset)))
```

---

### 4. ❌ N+1 Pattern in Tag Normalization
**Location:** `src/tunarr/scheduler/curation/tags.clj:62-66`
**Severity:** HIGH

```clojure
(doseq [tag (map name (catalog/get-tags catalog))]
  (let [cleaned (clean-tag tag)]
    (when-not (= tag cleaned)
      (log/info (format "renaming %s -> %s" tag cleaned))
      (catalog/rename-tag! catalog tag cleaned))))  ; ← Individual DB call per tag!
```

**Problem:**
- Sequential database updates for each tag that needs renaming
- Each `rename-tag!` is a separate transaction (lines 434-438 in sql_catalog.clj)
- Worse: `rename-tag!` does additional queries to check if target exists

**Impact:**
- For 1,000 tags needing normalization: 1,000+ database round-trips
- Each rename involves 2-3 queries (check existence, update/merge, delete)
- Total operations: 2,000-3,000+ for large tag sets

**Solution:**
Batch tag renames into a single transaction or use SQL CASE statements.

---

### 5. ❌ N+1 Pattern in Media Retagging
**Location:** `src/tunarr/scheduler/curation/core.clj:75-82`
**Severity:** HIGH

```clojure
(doseq [media library-media]
  (if (overdue? (catalog/get-media-process-timestamps catalog media) ...)  ; ← DB query per item!
      (do (log/info (format "re-tagging media: %s" (::media/name media)))
          (throttler/submit! throttler retag-media! ...))))
```

**Problem:**
- `get-media-process-timestamps` is called for each media item individually
- Even though throttled, the checking phase hits the database once per item
- Line 76: `catalog/get-media-process-timestamps` fetches from DB

**Impact:**
- For 10,000 media items: 10,000 database queries just to check timestamps
- This happens BEFORE any throttling/processing
- Adds seconds to minutes of overhead

**Solution:**
Fetch all timestamps in one query with a JOIN:
```clojure
;; Add method to fetch timestamps for entire library in one query
(defn get-library-media-with-timestamps [catalog library]
  ;; Single query with LEFT JOIN to media_process_timestamp table
  ...)
```

---

### 6. ❌ Blocking HTTP Calls in Sequential Loop
**Location:** `src/tunarr/scheduler/curation/core.clj:66-82`
**Severity:** MEDIUM-HIGH

```clojure
(defn retag-library-media! [brain catalog library throttler ...]
  (let [library-media (catalog/get-media-by-library catalog library)]
    (doseq [media library-media]
      ;; Each submission will eventually call tunabrain API
      (throttler/submit! throttler retag-media! ...))))
```

**Context:** The throttler (lines 34-48 in `throttler.clj`) processes jobs sequentially:
```clojure
(loop [next-run ...]
  (if-let [{:keys [job args callback]} (<!! jobs)]
    (do (try (let [result (apply job args)] ...)  ; ← Blocking!
```

**Problem:**
- The throttler executes jobs synchronously (blocks on each HTTP call)
- `retag-media!` calls `tunabrain/request-tags!` which is a blocking HTTP POST
- Rate limited to 2 requests/second, but each blocks the worker thread

**Impact:**
- For 1,000 items at 2 req/sec: minimum 500 seconds (8+ minutes)
- HTTP latency (100-500ms) adds to total time
- Worker thread blocked during I/O
- CPU sits idle while waiting for network

**Solution:**
Use async HTTP client or parallel workers with proper rate limiting:
```clojure
;; Option 1: Use async HTTP client (http-kit, aleph)
;; Option 2: Multiple throttler workers with shared rate limiter
```

---

### 7. ❌ Inefficient Array Aggregation with GROUP BY
**Location:** `src/tunarr/scheduler/media/sql_catalog.clj:324-340`
**Severity:** MEDIUM

```clojure
(select ...
  [[:array_agg [:distinct :media_tags.tag]] :tags]
  [[:array_agg [:distinct :media_channels.channel]] :channels]
  [[:array_agg [:distinct :media_genres.genre]] :genres]
  [[:array_agg [:distinct :media_taglines.tagline]] :taglines])
(from :media)
(left-join :media_tags [:= :media.id :media_tags.media_id])
(left-join :media_channels [:= :media.id :media_channels.media_id])
(left-join :media_genres [:= :media.id :media_genres.media_id])
(left-join :media_taglines [:= :media.id :media_taglines.media_id])
(group-by :media.id)
```

**Problem:**
- Four separate LEFT JOINs cause cartesian product expansion
- For 1 media with 5 tags, 3 channels, 4 genres, 2 taglines = 5×3×4×2 = 120 intermediate rows
- All collapsed back via GROUP BY and array_agg
- DISTINCT operations on arrays are expensive

**Impact:**
- Query performance degrades with more tags/genres per item
- Temporary result sets can be 10-100x larger than final results
- Index usage may be suboptimal due to multiple joins

**Solution:**
- Use JSON aggregation with subqueries instead of JOINs
- Or fetch base media first, then related data in separate optimized queries
- PostgreSQL-specific: Use LATERAL joins for better performance

---

## Medium Priority Issues

### 8. Potential Connection Pool Exhaustion
**Location:** `src/tunarr/scheduler/sql/executor.clj:94`

```clojure
(with-open [conn (jdbc/get-connection store)]
  (try (loop [{:keys [result] :as job} (<!! jobs)] ...)))
```

**Issue:** Each worker holds a database connection open indefinitely. With default 10 workers, that's 10 permanent connections. Under high load, this could exhaust the connection pool.

**Recommendation:** Configure appropriate connection pool size in PostgreSQL and application.

---

### 9. Unbounded Job Storage
**Location:** `src/tunarr/scheduler/jobs/runner.clj:102-105`

```clojure
(defrecord JobRunner [jobs]
  IJobRunner
  (add-job! [_ job-id job]
    (swap! jobs assoc job-id job)))  ; ← Grows unbounded!
```

**Issue:** The jobs atom accumulates all jobs forever. Never cleared (except on shutdown).

**Impact:** Memory leak over time. After 10,000 jobs, memory usage grows significantly.

**Recommendation:** Implement job TTL or max job history limit with LRU eviction.

---

### 10. Missing Database Indexes (Potential)
**Locations:** Various query patterns suggest these indexes should exist:

Required indexes:
- `media_tags(media_id, tag)` - used heavily in filters
- `media_channels(media_id, channel)` - used in filtering
- `media_process_timestamp(media_id, process)` - used for timestamp lookups
- `media(library_id)` - used for library filtering
- `media_categorization(media_id, category, category_value)` - composite index

**Note:** Verify with `EXPLAIN ANALYZE` on production queries.

---

## Performance Best Practices Violations

### Clojure-Specific Issues

1. **Lazy Sequence Realization**
   - Multiple uses of `(count items)` on lazy sequences forces full realization
   - Prefer `counted?` check or streaming approaches

2. **Blocking in Core.Async Contexts**
   - Throttler uses `<!!` (blocking take) which is appropriate for dedicated threads
   - But worker pool size is fixed - consider dynamic scaling

3. **Transaction Granularity**
   - Many small transactions instead of batching
   - Each `sql:exec-with-tx!` call is a separate transaction

---

## Recommendations Summary

### Immediate Actions (Critical)

1. **Implement batch operations for media sync** (Issue #1, #2)
   - Add `add-media-batch!` method
   - Process in chunks of 250-500 items
   - Estimated improvement: 100-1000x faster

2. **Add pagination to get-media queries** (Issue #3)
   - Default page size: 100-500 items
   - Implement cursor-based pagination for API

3. **Optimize tag operations** (Issue #4)
   - Batch tag renames into single transaction
   - Use SQL CASE statements for bulk updates

### Short-term Improvements

4. **Fix N+1 timestamp queries** (Issue #5)
   - Fetch library timestamps in single query
   - Add method: `get-library-media-with-timestamps`

5. **Optimize HTTP calls** (Issue #6)
   - Evaluate async HTTP client
   - Or implement parallel throttler workers

6. **Add database indexes** (Issue #10)
   - Run EXPLAIN ANALYZE on slow queries
   - Add composite indexes for common filters

### Long-term Optimizations

7. **Refactor complex JOIN query** (Issue #7)
   - Consider denormalization for read-heavy operations
   - Use materialized views for aggregated data

8. **Add job retention policy** (Issue #9)
   - Implement TTL for completed jobs
   - Or limit to last N jobs

9. **Connection pool tuning** (Issue #8)
   - Monitor connection usage
   - Adjust worker count and pool size together

---

## Benchmarking Recommendations

To validate these findings, run benchmarks with:

1. **Synthetic data**: 10K, 50K, 100K media items
2. **Metrics to track**:
   - Rescan time (current vs. batched)
   - Memory usage during sync
   - Database query times (EXPLAIN ANALYZE)
   - HTTP request throughput (with throttling)

3. **Tools**:
   - `criterium` for Clojure benchmarks
   - PostgreSQL `pg_stat_statements` for query analysis
   - VisualVM or YourKit for memory profiling

---

## Estimated Impact

| Issue | Current State (10K items) | After Fix | Improvement |
|-------|---------------------------|-----------|-------------|
| Media Sync N+1 | ~10-30 minutes | ~10-30 seconds | 60-180x faster |
| Memory Usage | 250MB+ | 10-50MB | 5-25x reduction |
| Tag Normalization | 1-5 minutes | 5-10 seconds | 6-60x faster |
| Query Performance | Multi-second | <100ms | 10-100x faster |

**Overall:** These fixes could reduce operation times from **hours to minutes** and memory usage by **10-100x** for large libraries.
