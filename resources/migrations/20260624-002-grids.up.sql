-- System-of-record storage for the layered-grid scheduler.
--
-- A frozen Grid is authored once per (channel, quarter, year) and is immutable:
-- re-authoring inserts a NEW version row rather than mutating the existing one,
-- and prior versions are marked 'superseded' for audit. Overrides are stored
-- per (channel, month), versioned the same way. The full Grid / Override[] and
-- the FeasibilityReport at freeze time are kept as JSON text (mirroring the
-- `strategies` table), so they round-trip exactly with the wire contracts.

CREATE TABLE IF NOT EXISTS grids (
    id           VARCHAR(64)  PRIMARY KEY,            -- internal row id (uuid)
    grid_id      VARCHAR(128),                        -- Tunabrain's grid_id (audit handle)
    channel      VARCHAR(128) NOT NULL,
    quarter      VARCHAR(8)   NOT NULL,               -- 'Q1'..'Q4'
    cal_year     INTEGER      NOT NULL,               -- 'year' is reserved in some engines (H2)
    version      INTEGER      NOT NULL DEFAULT 1,      -- increments per re-author
    status       VARCHAR(32)  NOT NULL DEFAULT 'frozen',
    grid         TEXT         NOT NULL,               -- full Grid contract, JSON
    feasibility  TEXT,                                -- FeasibilityReport at freeze, JSON
    created_at   TEXT         NOT NULL,
    UNIQUE (channel, quarter, cal_year, version)
);
--;;

CREATE INDEX IF NOT EXISTS idx_grids_channel_quarter
    ON grids (channel, cal_year, quarter, version DESC);
--;;

CREATE TABLE IF NOT EXISTS overrides (
    id            VARCHAR(64)  PRIMARY KEY,           -- internal row id (uuid)
    overrides_id  VARCHAR(128),                       -- Tunabrain's overrides_id (audit handle)
    channel       VARCHAR(128) NOT NULL,
    cal_month     VARCHAR(7)   NOT NULL,              -- 'YYYY-MM' ('month' is reserved in H2)
    version       INTEGER      NOT NULL DEFAULT 1,
    status        VARCHAR(32)  NOT NULL DEFAULT 'active',
    overrides     TEXT         NOT NULL DEFAULT '[]', -- Override[] contract, JSON
    created_at    TEXT         NOT NULL,
    UNIQUE (channel, cal_month, version)
);
--;;

CREATE INDEX IF NOT EXISTS idx_overrides_channel_month
    ON overrides (channel, cal_month, version DESC);
