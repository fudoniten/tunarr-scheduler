-- Persistent storage for LLM-generated scheduling strategies.
-- Complex fields (channel_adjustments, special_events, channels, raw) are
-- stored as JSON text; timestamps are kept as ISO-8601 strings to round-trip
-- exactly with the HTTP API representation.

CREATE TABLE IF NOT EXISTS strategies (
    id                  VARCHAR(64) PRIMARY KEY,
    period              VARCHAR(32) NOT NULL,
    status              VARCHAR(32) NOT NULL,
    strategy            TEXT        NOT NULL,
    channel_adjustments TEXT        NOT NULL DEFAULT '[]',
    special_events      TEXT        NOT NULL DEFAULT '[]',
    channels            TEXT        NOT NULL DEFAULT '[]',
    raw                 TEXT,
    error               TEXT,
    created_at          TEXT        NOT NULL,
    applied_at          TEXT,
    reverted_at         TEXT,
    restored_at         TEXT
);
--;;

CREATE INDEX IF NOT EXISTS idx_strategies_created_at
    ON strategies (created_at DESC);
--;;

CREATE INDEX IF NOT EXISTS idx_strategies_period_created
    ON strategies (period, created_at DESC);
--;;
