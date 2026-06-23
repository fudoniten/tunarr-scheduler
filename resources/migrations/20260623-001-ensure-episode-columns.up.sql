-- Idempotent migration: add episode support columns if they don't exist.
-- This ensures the schema is correct even if the 20260218-001-episode-support
-- migration failed or was skipped.

DO $$
BEGIN
    -- Add parent_id if missing
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'media' AND column_name = 'parent_id'
    ) THEN
        ALTER TABLE media ADD COLUMN parent_id VARCHAR(128) REFERENCES media(id) ON DELETE CASCADE;
        CREATE INDEX idx_media_parent_id ON media(parent_id);
    END IF;

    -- Add season_number if missing
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'media' AND column_name = 'season_number'
    ) THEN
        ALTER TABLE media ADD COLUMN season_number INT;
    END IF;

    -- Add episode_number if missing
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'media' AND column_name = 'episode_number'
    ) THEN
        ALTER TABLE media ADD COLUMN episode_number INT;
    END IF;

    -- Add episode order index if missing
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE indexname = 'idx_media_episode_order'
    ) THEN
        CREATE INDEX idx_media_episode_order ON media(parent_id, season_number, episode_number);
    END IF;
END $$;

--;;

-- Add constraint: episodes must have a parent, but only if the constraint doesn't exist
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE table_name = 'media' AND constraint_name = 'chk_episode_parent'
    ) THEN
        ALTER TABLE media
        ADD CONSTRAINT chk_episode_parent CHECK (
            (media_type = 'episode' AND parent_id IS NOT NULL)
            OR (media_type != 'episode')
        );
    END IF;
END $$;

--;;

-- Add constraint: episodes must have season and episode numbers, but only if the constraint doesn't exist
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE table_name = 'media' AND constraint_name = 'chk_episode_numbers'
    ) THEN
        ALTER TABLE media
        ADD CONSTRAINT chk_episode_numbers CHECK (
            (media_type = 'episode' AND season_number IS NOT NULL AND episode_number IS NOT NULL)
            OR (media_type != 'episode')
        );
    END IF;
END $$;
