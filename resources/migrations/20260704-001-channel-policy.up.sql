-- Per-channel content policy: deterministic HARD placement constraints.
--
-- Unlike channel_guidance (free-text hints that steer the LLM but never gate),
-- a policy is enforced by the deterministic layer — the feasibility checker
-- blocks a grid that violates it, and the publish step substitutes default
-- content at air time. Currently the policy holds a list of "watersheds"
-- (time-of-day restrictions on tagged content, e.g. audience:adult only after
-- 22:00). Stored as a JSON ContentPolicy blob, one current row per channel,
-- updated in place.

CREATE TABLE IF NOT EXISTS channel_policy (
    channel    VARCHAR(128) PRIMARY KEY,
    policy     TEXT NOT NULL DEFAULT '{}',  -- JSON contracts/ContentPolicy
    updated_at TEXT NOT NULL
);
