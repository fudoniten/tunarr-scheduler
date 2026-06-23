# tunarr-scheduler TODO

**Status as of Apr 27, 2026:** Migration to **Pseudovision** exclusive integration complete. All old code cleaned up, docs updated.

---

## ✅ Completed

### **Pseudovision Integration (Apr 19, 2026)**
- [x] Design document (PSEUDOVISION_INTEGRATION.md)
- [x] Pseudovision HTTP client (backends/pseudovision/client.clj)
  - Tag management APIs
  - Schedule creation APIs
  - Channel management APIs
  - Playout rebuild APIs
- [x] Tag sync module (media/pseudovision_sync.clj)
  - Sync catalog tags to Pseudovision
  - Jellyfin ID mapping
  - Job-based execution with progress reporting
- [x] Schedule generator (scheduling/pseudovision.clj)
  - Convert high-level specs to Pseudovision API calls
  - Support all fill modes and playback orders
  - Tag-based filtering (required/excluded tags)
- [x] Channel sync (channels/sync.clj)
  - Auto-create channels in Pseudovision from config
  - **14 channels synced successfully!**
- [x] Configuration updates
  - Replaced :backends (ErsatzTV/Tunarr) with :pseudovision
  - Wired into Integrant system
  - Health check validation on startup
- [x] API endpoints
  - `GET /api/version` - Version tracking
  - `POST /api/channels/sync-pseudovision` - Sync channels
  - `POST /api/media/:library/sync-pseudovision-tags` - Sync tags
  - `POST /api/channels/:channel-id/schedule` - Generate schedules

### **Core Features (Pre-existing)**
- [x] Jellyfin media collection integration
- [x] PostgreSQL catalog storage
- [x] Tunabrain LLM integration for categorization
- [x] Job runner for async operations
- [x] Tag management and curation
- [x] Category system (channel, time-slot, audience, season, freshness)

---

## 🔥 High Priority (Next Steps)

### **1. Complete End-to-End Flow** ⚡ IMMEDIATE
**Status:** Channel sync works, but tag sync blocked by catalog schema issues

- [ ] Fix catalog schema mismatch (`parent_id` column error)
- [ ] Test media rescan from Jellyfin
- [ ] Test LLM categorization/tagging workflow
- [ ] Test tag sync to Pseudovision
- [ ] Generate first schedule with tag-based filtering
- [ ] Verify streaming works end-to-end

**Estimated time:** 2-4 hours

### **2. Jellyfin ID Mapping**
**Status:** Currently returns empty map with warning

- [ ] Add Pseudovision query endpoint: `GET /api/media-items?remote_key={jellyfin-id}`
- [ ] Build efficient Jellyfin ID → Pseudovision media_item_id map
- [ ] Use map in tag sync to match items

**Estimated time:** 2 hours

### **3. Schedule Templates & Automation**
**Status:** Manual schedule creation works, but no templates

- [ ] Create schedule templates for each channel theme
  - Enigma TV: mystery/detective content
  - Toon Town: animated content
  - Galaxy: sci-fi
  - etc.
- [x] Add cron/scheduled task for daily schedule regeneration
      (done via k8s CronJobs → POST /api/scheduling/{daily,weekly,monthly,quarterly};
      see deploy/k8s)
- [ ] Add API endpoint: `POST /api/channels/all/regenerate-schedules`

**Estimated time:** 4-6 hours

---

## 📋 Medium Priority

### **4. Cleanup Old Code**
**Status:** ✅ Completed (Apr 27, 2026)

- [x] Delete `backends/ersatztv/` directory
- [x] Delete `backends/tunarr/` directory
- [x] Delete `media/jellyfin_sync.clj` (replaced by pseudovision_sync.clj)
- [x] Remove old test file `backends/ersatztv/mapping_test.clj`

### **5. Documentation Updates**
**Status:** ✅ Completed (Apr 27, 2026)

- [x] Update README.md - replace ErsatzTV with Pseudovision
- [x] Rename/update JELLYFIN_SYNC.md → PSEUDOVISION_SYNC.md
- [x] Update SCHEDULING.md to reflect Pseudovision architecture
- [x] Rewrite MIGRATION.md to reference new docs

### **6. Channel-Specific Collections**
**Status:** Can create schedules, but no automatic collection mapping

- [ ] Create Pseudovision smart collections for each channel theme
  - "All Mystery Movies" collection for Enigma TV
  - "All Animated Content" collection for Toon Town
  - etc.
- [ ] Map tunarr-scheduler channels to Pseudovision collections
- [ ] Update schedule generator to use appropriate collections

**Estimated time:** 3-4 hours

---

## 🔧 Low Priority (Future Enhancements)

### **7. Advanced Categorization**
- [ ] Implement engagement levels (background/casual/focused/immersive)
- [ ] Add duration categories (short/standard/feature/long)
- [ ] Day-of-week preferences (weekday/weekend)
- [ ] Implement category confidence scores

### **8. Schedule Versioning**
- [ ] Track schedule changes over time
- [ ] Allow rollback to previous schedules
- [ ] A/B testing for schedule variations

### **9. Analytics Integration**
- [ ] Track which items get scheduled most frequently
- [ ] Monitor category distribution across channels
- [ ] Report on tag coverage (% of library tagged)

### **10. Multi-Backend Support** (If Needed)
- [ ] Re-introduce backend protocol if other platforms need support
- [ ] Support both Pseudovision + other backends simultaneously

---

## 🐛 Known Issues

### **Catalog Schema**
- **Issue:** Retag job fails with "column media.parent_id does not exist"
- **Impact:** Can't run LLM categorization on existing catalog
- **Priority:** HIGH
- **Fix:** Migrate catalog schema or fix queries

### **Collections API (Pseudovision)**
- **Issue:** `/api/collections` returns JSON encoding error for PgArray
- **Impact:** Can't query available collections via API
- **Priority:** MEDIUM
- **Fix:** Add JSON encoder for PostgreSQL array types

### **Slot/Schedule API Format**
- **Issue:** Unclear API format for slot creation (kebab-case vs snake_case)
- **Impact:** Manual schedule creation requires trial and error
- **Priority:** MEDIUM
- **Fix:** Document API format or add request validation

---

## 🎯 Success Metrics

**✅ Achieved:**
- 14 channels created in Pseudovision via tunarr-scheduler
- Pseudovision ↔ tunarr-scheduler direct integration working
- Tag system fully implemented (API + scheduling engine)
- Channel sync automated and tested

**🎯 Next Milestones:**
- [ ] First channel with LLM-tagged content scheduled and streaming
- [ ] All 14 channels with curated schedules
- [ ] 24/7 continuous streaming with automatic daily schedule extension
- [ ] Full tag coverage of media library (90%+ items tagged)

---

## Architecture

```
┌─────────────────────┐
│  tunarr-scheduler   │
│  - Jellyfin sync    │
│  - LLM categorize   │
│  - Tag management   │
└──────────┬──────────┘
           │
           │ Direct API Integration
           │ (tags, schedules, playouts)
           ▼
┌─────────────────────┐
│   Pseudovision      │
│  - Tag storage      │
│  - Scheduling engine│
│  - HLS streaming    │
└──────────┬──────────┘
           │
           │ (both systems read from same Jellyfin)
           ▼
┌─────────────────────┐
│     Jellyfin        │
│  - Media server     │
│  - Metadata         │
└─────────────────────┘
```

**Key Benefits:**
- 1-hop integration (was 3-hop via Jellyfin tags)
- Native tag-based scheduling in Pseudovision
- Pseudovision owns schedules and playouts
- tunarr-scheduler focuses on curation and high-level scheduling

---

## Quick Reference: Pseudovision Integration

### **Tag Workflow:**
```bash
# 1. Sync media from Jellyfin
POST /api/media/movies/rescan

# 2. Categorize with LLM
POST /api/media/movies/retag

# 3. Sync tags to Pseudovision
POST /api/media/movies/sync-pseudovision-tags

# 4. Verify tags
GET https://pseudovision.kube.sea.fudo.link/api/tags
```

### **Schedule Workflow:**
```bash
# Create schedule for a channel
POST /api/channels/{channel-id}/schedule
Body: {
  "name": "Evening Comedy Block",
  "slots": [{
    "time": "18:00:00",
    "fill_mode": "block",
    "duration_hours": 2,
    "required_tags": ["comedy", "short"],
    "excluded_tags": ["explicit"],
    "playback_order": "shuffle"
  }],
  "horizon": 14
}
```

### **Channel Management:**
```bash
# Sync all configured channels to Pseudovision
POST /api/channels/sync-pseudovision

# Result: Creates 14 channels (numbers 2-15) with matching UUIDs
```

---

## Configuration Reference

**Channels (14 total):**
1. Enigma TV (2) - Mystery/detective
2. Toon Town (3) - Animated
3. Galaxy (4) - Sci-fi
4. Nippon TV (5) - Japanese
5. Sitcom Spectrum (6) - Sitcoms
6. Britannia (7) - British
7. Golden Reels (8) - Classic (pre-1970)
8. Spotlight (9) - Movies
9. Prime Series (10) - Prestige TV
10. InfoBytes (11) - Science/tech
11. Chronicles (12) - History
12. Muse (13) - Music
13. Tasty TV (14) - Food
14. Hua Network (15) - Chinese

**Category Dimensions:**
- `channel` - Which channel content fits
- `time-slot` - Best time of day (morning/daytime/primetime/late-night)
- `audience` - Target audience (kids/family/teen/adult)
- `season` - Best time of year (spring/summer/fall/winter/holiday/etc)
- `freshness` - Era (classic/retro/modern/contemporary)
