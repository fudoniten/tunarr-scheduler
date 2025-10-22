-- Add channel data to channel table

ALTER TABLE channel
  DROP COLUMN IF EXISTS id,
  DROP COLUMN IF EXISTS full_name;

--;;
