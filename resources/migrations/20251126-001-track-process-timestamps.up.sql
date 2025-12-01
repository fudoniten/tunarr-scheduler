-- Add process timestamp tables

CREATE TABLE media_process_timestamp (
  media_id VARCHAR(128) REFERENCES media(id) ON DELETE CASCADE,
  process VARCHAR(128) NOT NULL,
  last_run_at timestamptz,
  PRIMARY KEY (media_id, process)
);
