# TV Scheduling System Design

This document outlines the architecture for an LLM-powered TV scheduling system that manages ErsatzTV channels with minimal human intervention.

## Philosophy

- **Agent has full control** - no manual/auto block distinction
- **Monthly cadence** - stable schedules, infrequent reshuffles
- **Constraint-driven** - users provide natural language instructions
- **Pleasant surprises** - occasional reshuffles (1-2x/year), no approval needed
- **Disciplined** - consistent structure, not chaotic changes
- **Always something playing** - ErsatzTV fallback ensures no dead air

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                      USER INSTRUCTIONS                          │
│  (Web UI → stored in DB per channel)                            │
│                                                                 │
│  Natural language, e.g.:                                        │
│    "Seinfeld should air at least 3x/week in primetime"          │
│    "Weekend mornings: light, family-friendly content"           │
│    "No adult content before 10pm"                               │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│               LEVEL 0: SEASONAL PLANNER                         │
│               (quarterly, or on-demand via "big red button")    │
├─────────────────────────────────────────────────────────────────┤
│  Decides:                                                       │
│    • Theme weeks (Spy Week, Action Week)                        │
│    • Holiday scheduling (Christmas movies Dec 15-25)            │
│    • Marathon weekends                                          │
│    • Major lineup reshuffles (1-2x/year)                        │
│                                                                 │
│  Constraints:                                                   │
│    • Max 1-2 theme weeks per month                              │
│    • Reshuffles only on calendar triggers or manual request     │
│    • Must respect content inventory limits                      │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│               LEVEL 1: MONTHLY PLANNER                          │
│               (runs ~1st of each month)                         │
├─────────────────────────────────────────────────────────────────┤
│  Decides:                                                       │
│    • Weekly block templates (recurring structure)               │
│    • Show assignments to recurring slots                        │
│    • Special event blocks from seasonal plan                    │
│    • Gap-filler strategy per time-of-day                        │
│                                                                 │
│  Example output:                                                │
│    Mon-Fri 18:00-19:00: Seinfeld (sequential)                   │
│    Mon-Fri 19:00-20:00: Friends (sequential)                    │
│    Mon-Fri 20:00-22:00: Rotating comedies (shuffle)             │
│    Sat 08:00-12:00: Classic sitcom marathon                     │
│    Sat 20:00-00:00: Movie night                                 │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│               LEVEL 2: WEEKLY EXECUTOR                          │
│               (runs weekly)                                     │
├─────────────────────────────────────────────────────────────────┤
│  Decides:                                                       │
│    • Concrete media for variable/shuffle slots                  │
│    • Episode numbers for sequential shows                       │
│    • Adjustments for that specific week                         │
│                                                                 │
│  Actions:                                                       │
│    • Generate ErsatzTV Sequential Schedule YAML                 │
│    • Upload to ErsatzTV via API                                 │
│    • Update episode tracking state                              │
│    • Trigger playout rebuild                                    │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                        ERSATZTV                                 │
├─────────────────────────────────────────────────────────────────┤
│  Dead Air Fallback: Smart collection per channel                │
│    → Always something playing, even if scheduler fails          │
│  Sequential Schedule: Agent-generated YAML                      │
│  Playout: Built from schedule + fallback                        │
└─────────────────────────────────────────────────────────────────┘
```

## User Instructions

Instructions are stored per-channel in the database as text blobs, editable via web UI. They use natural language so non-technical users can contribute.

### Example Instructions

```
Channel: Sitcom Spectrum

Recurring Shows:
- Seinfeld should air at least 3 times per week during primetime
- Friends should have a regular evening slot
- The Office is good for late-night

Time-of-Day Rules:
- Weekend mornings (8am-12pm): light, family-friendly sitcoms
- Primetime (8pm-11pm): popular shows, variety
- Late night (11pm+): edgier content is okay

Preferences:
- Multi-episode blocks are preferred (2-3 episodes back-to-back)
- Don't put Friends and Seinfeld back-to-back
- Variety across the week - don't repeat same show same timeslot
- 30-minute time slots as the base unit
```

### Instruction Categories

1. **Recurring show requirements** - minimum frequency, preferred timeslots
2. **Time-of-day rules** - content appropriateness per time slot
3. **Structural preferences** - block sizes, variety, adjacency rules
4. **Special instructions** - holiday content, seasonal themes

## Content Categories

Media is pre-categorized (via the recategorize system) into:

| Category | Values | Purpose |
|----------|--------|---------|
| `time-slot` | daytime, primetime, late-night | Time-appropriateness filtering |
| `audience` | kids, family, teen, adult | Daytime-safe filtering |
| `season` | any, spring, summer, fall, winter, holiday, halloween, valentines, independence | Seasonal scheduling |
| `freshness` | classic, retro, modern, contemporary | Era-based channel targeting |

### Category Assignment Examples

**Spooky content** (flexible):
- season: `[:halloween, :any]` - Can air during Halloween OR year-round

**Christmas movie** (specific):
- season: `[:holiday]` - Only December

**Family sitcom**:
- time-slot: `[:daytime, :primetime]`
- audience: `[:family]`

## Episode Tracking

The system tracks progress through sequential shows:

```
Series: Seinfeld
  Current: Season 4, Episode 12
  Total Episodes: 180
  Last Aired: 2026-02-01
  Status: active

Series: Cheers  
  Current: Season 11, Episode 28 (final)
  Total Episodes: 275
  Last Aired: 2026-01-15
  Status: completed → rotated out
```

### Series Lifecycle

1. **Active** - Currently in rotation, episodes advance sequentially
2. **Completed** - All episodes played, automatically rotated out for a while
3. **Resting** - Temporarily removed (will return based on agent decisions)
4. **Retired** - Manually removed from rotation

When a series completes:
- Default: Rotate out to avoid staleness
- Unless: User instructions explicitly say "always keep Seinfeld, restart when done"
- The agent won't directly know when rotation happens - it will naturally select different content when generating schedules

## Reshuffle Triggers

Major schedule reshuffles happen on:

### 1. Calendar Events (Automatic)
- Quarterly (seasonal changes)
- New Year
- Major holidays (if configured)

### 2. Manual Trigger (User)
- "Big red button" in web UI
- Triggers Level 0 Seasonal Planner
- No approval needed - agent executes immediately

### 3. NOT Triggered By
- New content being added (too frequent)
- Series completing (handled by rotation)
- Weekly schedule runs (those use existing templates)

## Block Structure

### Time Slot Granularity

- Base unit: **30 minutes**
- Multi-episode blocks preferred (60-90 min for sitcoms)
- Movies get flexible duration (round to nearest 30 min)

### Block Types

| Type | Duration | Content Selection | Example |
|------|----------|-------------------|---------|
| Sequential Show | 30-90 min | Next episodes in order | "Seinfeld S4E12-E13" |
| Shuffle Block | 60-180 min | Random from criteria | "Classic sitcoms (shuffle)" |
| Marathon | 4-12 hours | Theme-based, shuffled | "Spy Movie Marathon" |
| Movie Slot | 90-180 min | Single film | "Random primetime movie" |
| Filler | Variable | Random appropriate content | "Channel-appropriate fallback" |

## ErsatzTV Integration

### Schedule Format

The agent generates ErsatzTV Sequential Schedule YAML:

```yaml
content:
  # Morning sitcom block (multi-episode)
  - sequence:
      name: "Morning Sitcoms"
      content:
        - show:
            key: "FRIENDS"
            show_guids: ["friends-guid"]
            order: "chronological"
            count: 2  # 2 episodes back-to-back
        - show:
            key: "SEINFELD"  
            show_guids: ["seinfeld-guid"]
            order: "chronological"
            count: 2
      playout:
        - pad_to_next: 30  # Pad to next 30-min mark

  # Primetime variety (shuffle)
  - sequence:
      name: "Primetime Comedy"
      content:
        - search:
            query: "channel:spectrum AND time_slot:primetime"
            order: "shuffle"
      playout:
        - duration: 180  # 3 hours
          trim: false

  # Weekend marathon
  - wait_until: "Saturday 8:00 AM"
  - sequence:
      name: "Saturday Morning Cartoons"
      content:
        - search:
            query: "channel:toontown AND audience:kids"
            order: "shuffle"
      playout:
        - duration: 240  # 4 hours
```

### Fallback Configuration

Each channel has smart collections configured in ErsatzTV for fallback:

**Daytime Fallback:**
```
Collection: random-spectrum-daytime
Query: channel:spectrum AND time_slot:(daytime OR primetime) AND audience:(family OR kids)
```

**Late-Night Fallback:**
```
Collection: random-spectrum-latenight  
Query: channel:spectrum AND time_slot:late-night
```

These ensure content always plays, even if:
- Scheduler fails to run
- Gaps exist between scheduled blocks
- Content runs short
- Agent makes a mistake

## Agent Tools

The scheduling agent needs these capabilities:

### Content Inventory
```
get_content_inventory(channel, filters) → {tag: count, ...}
get_shows_for_channel(channel) → [{show_id, title, episode_count, tags}, ...]
get_media_by_criteria(channel, tags, categories, limit) → [media]
get_movies_by_duration(channel, min_duration, max_duration) → [movies]
```

### Episode Tracking
```
get_series_progress(show_id) → {season, episode, total, status, last_aired}
advance_series(show_id, count) → updated_progress
get_active_series(channel) → [show_ids]
rotate_out_series(show_id) → success
rotate_in_series(show_id) → success
```

### ErsatzTV
```
get_current_schedule(channel) → yaml_content
upload_schedule(channel, yaml) → success
rebuild_playout(channel) → success
get_smart_collections() → [collections]
get_channel_info(channel) → {fallback_collection, streaming_mode, ...}
```

### Scheduling Helpers
```
validate_against_instructions(schedule, instructions) → {valid, issues}
get_upcoming_events(date_range) → [holidays, special_dates]
check_content_exhaustion(theme, duration) → {feasible, warnings}
```

### Manual Triggers
```
trigger_reshuffle(channel, reason) → job_id
get_schedule_preview(channel, date_range) → rendered_schedule
```

## Implementation Phases

### Phase 1: Foundation ⏱️ ~1-2 weeks
- [ ] Implement ErsatzTV API client (currently stub in `backends/ersatztv/client.clj`)
- [ ] Episode tracking database schema and CRUD operations
- [ ] User instruction storage schema (TEXT field per channel)
- [ ] Basic web UI for editing instructions
- [ ] Smart collection setup per channel in ErsatzTV

### Phase 2: Weekly Executor (Level 2) ⏱️ ~2-3 weeks
- [ ] Template → YAML generation logic
- [ ] Episode advancement logic
- [ ] ErsatzTV upload integration
- [ ] Validation against user instructions
- [ ] HTTP endpoint: `POST /api/scheduling/:channel/generate-week`

### Phase 3: Monthly Planner (Level 1) ⏱️ ~2-3 weeks
- [ ] LLM agent that generates weekly templates from instructions
- [ ] Instruction parsing and constraint checking
- [ ] Special event handling (holidays, theme weeks)
- [ ] Content inventory integration
- [ ] HTTP endpoint: `POST /api/scheduling/:channel/generate-month`

### Phase 4: Seasonal Planner (Level 0) ⏱️ ~1-2 weeks
- [ ] Theme week planning logic
- [ ] Marathon scheduling
- [ ] Holiday content management
- [ ] Lineup reshuffle logic (1-2x/year)
- [ ] HTTP endpoint: `POST /api/scheduling/:channel/reshuffle` (big red button)

### Phase 5: Polish ⏱️ ~1-2 weeks
- [ ] Full web UI for instructions and schedule preview
- [ ] Schedule review/preview before execution
- [ ] Better logging and monitoring
- [ ] Error recovery and retry logic

### Phase 6: Multi-Channel Coordination (Stretch Goal) ⏱️ TBD
- [ ] Avoid scheduling same content on multiple channels simultaneously
- [ ] Coordinate theme weeks across channels
- [ ] Share marathon content appropriately

## Data Models

### User Instructions (Database)

```sql
CREATE TABLE channel_instructions (
  channel_id VARCHAR(128) PRIMARY KEY REFERENCES channel(name) ON DELETE CASCADE,
  instructions TEXT NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT NOW()
);
```

### Episode Tracking

```sql
CREATE TABLE series_progress (
  show_id VARCHAR(128) PRIMARY KEY,
  show_name VARCHAR(512) NOT NULL,
  channel VARCHAR(128) NOT NULL REFERENCES channel(name),
  current_season INT NOT NULL DEFAULT 1,
  current_episode INT NOT NULL DEFAULT 1,
  total_episodes INT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'completed', 'resting', 'retired')),
  last_aired DATE,
  created_at timestamptz NOT NULL DEFAULT NOW(),
  updated_at timestamptz NOT NULL DEFAULT NOW()
);
```

### Schedule State

```sql
CREATE TABLE schedule_state (
  id BIGSERIAL PRIMARY KEY,
  channel VARCHAR(128) NOT NULL REFERENCES channel(name),
  generated_at timestamptz NOT NULL DEFAULT NOW(),
  schedule_type VARCHAR(20) NOT NULL CHECK (schedule_type IN ('weekly', 'monthly', 'seasonal')),
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  yaml_content TEXT NOT NULL,
  applied BOOLEAN NOT NULL DEFAULT false,
  applied_at timestamptz,
  notes TEXT
);
```

## Example User Instructions

### Sitcom Spectrum

```
Recurring Shows:
- Seinfeld should air at least 3 times per week during primetime
- Friends should have a regular evening slot
- The Office is good for late-night

Time-of-Day Rules:
- Weekend mornings (8am-12pm): light, family-friendly sitcoms
- Primetime (8pm-11pm): popular shows, variety
- Late night (11pm+): edgier content is okay

Preferences:
- Multi-episode blocks are preferred (2-3 episodes back-to-back)
- Don't put Friends and Seinfeld back-to-back
- Variety across the week - don't repeat same show same timeslot
- 30-minute time slots as the base unit
```

### Toon Town

```
Content Rules:
- Saturday mornings (8am-12pm): classic cartoons, Disney movies
- Weekday afternoons (3pm-6pm): kid-friendly anime and animated series
- Evenings (7pm-9pm): family animation (Pixar, DreamWorks)
- Late night (10pm+): adult animation (Futurama, Rick & Morty)

Special Events:
- Christmas season: Disney holiday specials
- Halloween: spooky but kid-appropriate (Scooby-Doo, etc.)

Always:
- No content rated above TV-PG before 9pm
- Multi-episode blocks for series
```

### Golden Reels

```
Content Focus:
- Only classic (pre-1970) and retro (1970s-1990s) content
- Prefer black-and-white films during daytime
- Color films in evening slots

Structure:
- Weekday mornings: classic sitcoms (I Love Lucy, etc.)
- Weekday afternoons: westerns
- Evenings: classic films (noirs, screwball comedies)
- Weekend afternoons: Charlie Chaplin/Buster Keaton marathons

When Seinfeld completes its run:
- Keep it in rotation, restart from S1E1
```

## ErsatzTV Sequential Schedule Example

Full example of what the agent would generate:

```yaml
# Generated by tunarr-scheduler for Sitcom Spectrum
# Week of: 2026-02-02 to 2026-02-08

content:
  # Monday through Friday pattern
  - sequence:
      name: "Weekday Evening Block"
      repeat: 5  # Mon-Fri
      content:
        # 6pm Seinfeld
        - wait_until: "18:00"
        - show:
            key: "SEINFELD_EVENING"
            show_guids: ["seinfeld-tvdb-2910"]
            order: "chronological"
            count: 2  # 2 episodes (60 min)
            
        # 7pm Friends  
        - show:
            key: "FRIENDS_EVENING"
            show_guids: ["friends-tvdb-1668"]
            order: "chronological"
            count: 2  # 2 episodes (60 min)
            
        # 8-10pm rotating comedies
        - search:
            query: "channel:spectrum AND time_slot:primetime AND NOT show_title:(Seinfeld OR Friends)"
            order: "shuffle"
        - duration: 120  # 2 hours
          trim: false
          
  # Saturday
  - wait_until: "Saturday 08:00"
  - sequence:
      name: "Saturday Morning Sitcom Marathon"
      content:
        - search:
            query: "channel:spectrum AND audience:(family OR kids)"
            order: "shuffle"
      playout:
        - duration: 240  # 4 hours (8am-12pm)
          
  - wait_until: "Saturday 20:00"
  - sequence:
      name: "Saturday Movie Night"
      content:
        - search:
            query: "channel:spectrum AND media_type:movie AND time_slot:(primetime OR late-night)"
            order: "shuffle"
            count: 2  # 2 movies
      playout:
        - pad_to_next: 30
        
  # Sunday
  - wait_until: "Sunday 14:00"
  - sequence:
      name: "Sunday Afternoon Movies"
      content:
        - search:
            query: "channel:spectrum AND media_type:movie AND audience:family"
            order: "shuffle"
            count: 2
```

## Reshuffle Behavior

### Calendar-Based Reshuffles

The Seasonal Planner (Level 0) automatically runs on:

- **January 1** - New year, fresh lineup
- **April 1** - Spring refresh
- **July 1** - Summer programming
- **October 1** - Fall lineup

During these runs, the agent may:
- Shuffle primetime show order
- Introduce new recurring shows
- Rotate out completed series
- Adjust timeslots based on content inventory changes

### Manual Reshuffle ("Big Red Button")

Web UI provides a reshuffle trigger per channel:

```
POST /api/scheduling/:channel/reshuffle
{
  "reason": "User requested fresh lineup",
  "preserve_favorites": false  // optional: keep certain shows in place
}
```

The agent will:
1. Re-run Seasonal Planner for next 3 months
2. Generate new monthly plan
3. Create new weekly schedules
4. Upload to ErsatzTV
5. Trigger playout rebuild

## Future Considerations

### Multi-Channel Coordination (Stretch Goal)

The agent could be aware of other channels when scheduling:

- Don't schedule Star Trek on Galaxy AND Spotlight simultaneously
- Coordinate theme weeks (Spy Week on Enigma, Action Week on Spotlight)
- Share marathon content across channels appropriately

### Feedback Integration

Potential future enhancements:

- Track what actually played vs. what was scheduled
- Learn from manual overrides (if users edit ErsatzTV directly)
- Adjust based on (hypothetical) viewership data
- User feedback: "that marathon was too long"

### Bumper Generation

Auto-generate channel promotions:

- "Coming up next" bumpers
- Theme-appropriate interstitials
- Schedule-aware announcements ("Tonight at 8...")

## Current Implementation Status

| Component | Status | Location |
|-----------|--------|----------|
| Backend protocol | ✅ Implemented | `src/tunarr/scheduler/backends/protocol.clj` |
| ErsatzTV mapping | ✅ Implemented | `src/tunarr/scheduler/backends/ersatztv/mapping.clj` |
| ErsatzTV API client | ⏳ Stub only | `src/tunarr/scheduler/backends/ersatztv/client.clj` |
| Episode tracking | ❌ Not implemented | Planned |
| User instructions | ❌ Not implemented | Planned |
| Schedule YAML generation | ❌ Not implemented | Planned |
| Scheduling agent | ❌ Not implemented | Planned |
| Web UI | ❌ Not implemented | Planned |

See `MIGRATION.md` for detailed migration plan from ErsatzTV to full scheduling system.
