# TV Scheduling System Roadmap

**Goal:** Automated, LLM-driven TV channel scheduling via Pseudovision

**Architecture:**
```
Radarr/Sonarr → Jellyfin (raw media + IMDB tags)
                    ↓ sync
              Pseudovision (media + channels)
                    ↓ pull (via Tunarr Scheduler)
              Tunarr Scheduler (enrich via Wikipedia → LLM → tags/suggestions/taglines)
                    ↓ push
              Pseudovision (schedules)
                    ↓ consume
              Clients (HLS playback)
```

---

## Blockers by Phase

### Phase 1: Fix Data Flow ✅ COMPLETE

| # | Blocker | Description | Status |
|---|---------|-------------|--------|
| 1.1 | Jellyfin direct access | Tunarr Scheduler pulls from Jellyfin instead of Pseudovision | ✅ Fixed (commit 6733876) |
| 1.2 | No Pseudovision collection | Missing `pseudovision_collection.clj` implementing MediaCollection protocol | ✅ Created (commit 6733876) |
| 1.3 | jellyfin-sidekick dead code | Sidecar failing due to Jellyfin tag API bugs | ✅ Removed (infra commit 926f953) |
| 1.4 | Jellyfin config in infra | Leftover config in tunarr-scheduler deployment | ✅ Fixed (infra commit 3343c3a) |

**Completed Actions:**
- ✅ Created `src/tunarr/scheduler/media/pseudovision_collection.clj`
- ✅ Updated collection config from `:jellyfin` to `:pseudovision`
- ✅ Removed jellyfin-sidekick sidecar from Jellyfin deployment
- ✅ Removed Jellyfin config from tunarr-scheduler deployment
- ✅ Deleted Jellyfin direct API code (jellyfin_collection.clj)
- ✅ Updated job runner to use `:media/pseudovision-sync` job type
- ✅ Fixed ersatztv/tunarr backend stubs that were broken

---

### Phase 2: Enable Tag Pipeline 🟡 HIGH

| # | Blocker | Description | Effort |
|---|---------|-------------|--------|
| 2.1 | Catalog schema issue | `parent_id` column missing causes retag failures | Low |
| 2.2 | End-to-end tag sync | Test `sync-pseudovision-tags` flow | Low |
| 2.3 | Jellyfin ID mapping | Verify `remote_key` mapping between systems works | Low |
| 2.4 | Wikipedia enrichment | Wire Wikipedia data from Pseudovision → TunaBrain | Medium |

**Actions:**
- Fix or document catalog migration for `parent_id`
- Test tag sync on a small library
- Verify media item counts match between systems
- Ensure TunaBrain can access Wikipedia for all media

---

### Phase 3: Schedule Generation 🟠 MEDIUM

| # | Blocker | Description | Effort |
|---|---------|-------------|--------|
| 3.1 | Level 2 executor | `schedule-week!` throws not implemented | High |
| 3.2 | Level 1 planner | Monthly block templates, show assignments | High |
| 3.3 | Level 0 planner | Seasonal themes, holidays, reshuffles | High |
| 3.4 | Schedule push | Push generated schedules to Pseudovision | Medium |
| 3.5 | Channel instructions | User constraints storage (DB + UI) | Medium |

**Actions:**
- Implement 3-level planning (Seasonal → Monthly → Weekly)
- Push schedules via `POST /api/schedules` + slots
- Store per-channel natural language instructions
- Add API for schedule preview and manual trigger

---

### Phase 4: Automation 🟢 LOW

| # | Blocker | Description | Effort |
|---|---------|-------------|--------|
| 4.1 | Episode tracking | Track progress through sequential shows | Medium |
| 4.2 | Schedule automation | ✅ k8s CronJobs → /api/scheduling/* (see deploy/k8s) | Low |
| 4.3 | Multi-channel coordination | Avoid scheduling same content simultaneously | Medium |
| 4.4 | Lookahead maintenance | Keep 3-4 weeks ahead always | Low |

**Actions:**
- Add `series_progress` table for episode tracking
- Create `/api/channels/all/regenerate-schedules` endpoint
- Add overlap detection in schedule generator
- Implement horizon extension (add next week as current week ends)

---

## Current System Status

### ✅ Working
- Media ingestion: Radarr/Sonarr → Jellyfin → Pseudovision
- **Data flow: Tunarr Scheduler now pulls from Pseudovision** (Phase 1 complete)
- 14 channels defined in Pseudovision
- TunaBrain: Wikipedia enrichment + LLM tagging
- Pseudovision: scheduling engine (needs input), HLS streaming
- Tag curation rules (transform/rename)

### ❌ Needs Work
- Everything after tagging: schedule generation is a skeleton
- Tag pipeline: needs end-to-end testing (Phase 2)
- Parent-child relationships in catalog may need schema fix

---

## Next Quick Wins (Phase 2)

1. ~~**Remove jellyfin-sidekick**~~ ✅ Done - Removed broken sidecar code
2. **Test Pseudovision sync** - `POST /api/media/movies/sync-pseudovision-tags`
3. **Verify media counts** - Compare catalog counts vs Pseudovision API
4. **Test tag round-trip** - Tag an item, sync back to Pseudovision, verify
5. **Create first schedule** - `POST /api/channels/6/schedule` with manual slot spec
6. **Verify playback** - Check HLS stream for scheduled channel

---

## Success Metrics

- [x] **Phase 1:** Data flow from Pseudovision working (commits 6733876, 7b5bf14)
- [ ] **Phase 2:** Tag enrichment pipeline end-to-end tested
- [ ] **Phase 3:** First channel with LLM-tagged content scheduled and streaming
- [ ] **Phase 3:** All 14 channels with curated schedules
- [ ] **Phase 4:** 24/7 continuous streaming with automatic lookahead
- [ ] **Phase 4:** 90%+ media library tag coverage

---

## Related Documentation

- [SCHEDULING.md](SCHEDULING.md) - Detailed scheduling system design
- [PSEUDOVISION_INTEGRATION.md](PSEUDOVISION_INTEGRATION.md) - Integration design
- [TODO.md](TODO.md) - Detailed task list