-- Per-media grounding context for Tunabrain tagging/categorization.
--
-- Tunabrain is stateless: it grounds tagging/categorization on a MediaContext
-- (a resolved Wikipedia summary, operator notes, or reference links) but never
-- persists it. This table is that persistence. After every /tags and
-- /categorize call the returned context is stored here so a bad auto-match is
-- inspectable; operators can then edit it, and the corrected context is sent
-- back on subsequent calls.
--
-- One row per media item. `links` is a JSON-encoded array of URL strings
-- (stored as text so the schema is portable across Postgres and the H2 test
-- database). `operator_edited` marks a human correction as sticky so an
-- automatic re-tag does not clobber it.

CREATE TABLE media_context (
  media_id        VARCHAR(128) PRIMARY KEY REFERENCES media(id) ON DELETE CASCADE,
  text            TEXT,
  links           TEXT NOT NULL DEFAULT '[]',
  summary         TEXT,
  source          VARCHAR(64),
  operator_edited BOOLEAN NOT NULL DEFAULT FALSE,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

--;;
