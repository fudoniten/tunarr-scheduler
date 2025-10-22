-- Add channel data to channel table

ALTER TABLE channel
  ADD COLUMN id VARCHAR(128) NOT NULL,
  ADD COLUMN full_name VARCHAR(128) NOT NULL;

--;;
