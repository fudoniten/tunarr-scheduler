-- Per-channel operator guidance for schedule generation.
--
-- This is the "manual input" surface: free-text steering and planned events the
-- operator can set per channel. It does NOT gate generation — the orchestration
-- pulls these fields into the Tunabrain propose-* requests (strategic_guidance,
-- quarterly_theme / monthly_theme, planned_events) when it runs. One current row
-- per channel, updated in place.

CREATE TABLE IF NOT EXISTS channel_guidance (
    channel            VARCHAR(128) PRIMARY KEY,
    strategic_guidance TEXT,
    quarterly_theme    TEXT,
    monthly_theme      TEXT,
    planned_events     TEXT NOT NULL DEFAULT '[]',  -- JSON array of strings
    updated_at         TEXT NOT NULL
);
