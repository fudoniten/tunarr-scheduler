-- Drop the item_kind column and revert to original episode constraints

-- Remove new indexes
DROP INDEX IF EXISTS idx_filler_library_tags;
DROP INDEX IF EXISTS idx_media_item_kind_library;
DROP INDEX IF EXISTS idx_media_item_kind;

--;;

-- Remove new constraints
ALTER TABLE media DROP CONSTRAINT IF EXISTS chk_episode_parent_with_filler;
ALTER TABLE media DROP CONSTRAINT IF EXISTS chk_episode_numbers_with_filler;

--;;

-- Restore original constraints
ALTER TABLE media
ADD CONSTRAINT chk_episode_parent CHECK (
    (media_type = 'episode' AND parent_id IS NOT NULL)
    OR (media_type != 'episode')
);

--;;

ALTER TABLE media
ADD CONSTRAINT chk_episode_numbers CHECK (
    (media_type = 'episode' AND season_number IS NOT NULL AND episode_number IS NOT NULL)
    OR (media_type != 'episode')
);

--;;

-- Drop the item_kind column
ALTER TABLE media DROP COLUMN IF EXISTS item_kind;