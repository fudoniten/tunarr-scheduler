# Migration Guide

This document has been superseded by more focused migration documentation.

## Current Architecture

The system now uses **Pseudovision** exclusively as the IPTV backend. See the following documents for details:

- **[PSEUDOVISION_INTEGRATION.md](PSEUDOVISION_INTEGRATION.md)** - Technical design and implementation details
- **[PSEUDOVISION_MIGRATION.md](PSEUDOVISION_MIGRATION.md)** - One-time migration from local catalog to Pseudovision
- **[ROADMAP.md](ROADMAP.md)** - Current status and next steps

## Architecture Summary

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
           ▼
┌─────────────────────┐
│     Jellyfin        │
│  - Media server     │
└─────────────────────┘
```

## Historical Context

This project was previously known as **tunarr-scheduler** and originally integrated with:
- **ErsatzTV** - IPTV streaming platform
- **Tunarr** - Channel management system

These integrations have been removed in favor of direct Pseudovision integration.

### Why the Change?

1. **Simplified Architecture** - Direct 1-hop integration vs 3-hop via Jellyfin
2. **Native Tag Support** - Pseudovision has built-in tag-based slot filtering
3. **Better Control** - Full control over scheduling engine
4. **Fewer Dependencies** - No need for Jellyfin tag sync workaround

## Migration Status

Completed on **April 19, 2026**:

- [x] Remove ErsatzTV/Tunarr backend code
- [x] Implement Pseudovision HTTP client
- [x] Add channel sync to Pseudovision
- [x] Add tag sync to Pseudovision
- [x] Update configuration schema
- [x] Wire into Integrant system

## If You Were Using ErsatzTV/Tunarr

### Old Workflow
1. Tag media in tunarr-scheduler
2. Sync tags to Jellyfin via API
3. ErsatzTV pulls tags from Jellyfin
4. Create smart collections in ErsatzTV
5. Upload sequential schedules to ErsatzTV

### New Workflow
1. Tag media in tunarr-scheduler
2. Sync tags directly to Pseudovision
3. Create schedules via Pseudovision API with tag filters
4. Pseudovision handles playout and streaming

## References

- **API Endpoints**: See `PSEUDOVISION_INTEGRATION.md`
- **Tag Migration**: See `PSEUDOVISION_MIGRATION.md`
- **Current roadmap**: See `ROADMAP.md`