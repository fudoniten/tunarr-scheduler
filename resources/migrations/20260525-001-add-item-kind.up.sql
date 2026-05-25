-- Add item_kind column to support filler media type
-- This enables YouTube/orphaned content to bypass episode constraints

ALTER TABLE media 
ADD COLUMN item_kind VARCHAR(20) 
DEFAULT 'episode' 
CHECK (item_kind IN ('episode', 'series', 'movie', 'filler'));

--;;

-- Retroactively classify existing data based on current structure
UPDATE media SET item_kind = CASE
    -- Episodes with parent remain episodes
    WHEN media_type = 'episode' AND parent_id IS NOT NULL THEN 'episode'
    
    -- Series without parent that have children become series
    WHEN media_type = 'series' AND parent_id IS NULL AND 
         EXISTS (SELECT 1 FROM media m2 WHERE m2.parent_id = media.id) THEN 'series'
         
    -- Movies without parent and no children remain movies
    WHEN media_type = 'movie' AND parent_id IS NULL THEN 'movie'
    
    -- Everything else (orphaned items, malformed data) becomes filler
    ELSE 'filler'
END;

--;;

-- Add performance indexes for filler queries
CREATE INDEX idx_media_item_kind ON media(item_kind);

--;;

CREATE INDEX idx_media_item_kind_library ON media(library_id, item_kind);

--;;

-- Composite index specifically for filler content queries
CREATE INDEX idx_filler_library_tags ON media(library_id, item_kind) 
WHERE item_kind = 'filler';

--;;

-- Drop the old constraint that requires episodes to have season/episode numbers
ALTER TABLE media DROP CONSTRAINT IF EXISTS chk_episode_numbers;

--;;

-- Add new constraint that allows filler to bypass episode number requirements
ALTER TABLE media
ADD CONSTRAINT chk_episode_numbers_with_filler CHECK (
    -- Episodes must have parent, season, and episode numbers
    (media_type = 'episode' AND item_kind = 'episode' AND parent_id IS NOT NULL AND season_number IS NOT NULL AND episode_number IS NOT NULL)
    OR 
    -- Filler items can have any structure (no requirements)
    (item_kind = 'filler')
    OR 
    -- Non-episodes are not constrained by episode rules
    (media_type != 'episode')
);

--;;

-- Update the parent constraint to also allow filler flexibility
ALTER TABLE media DROP CONSTRAINT IF EXISTS chk_episode_parent;

--;;

ALTER TABLE media
ADD CONSTRAINT chk_episode_parent_with_filler CHECK (
    -- Episodes must have a parent
    (media_type = 'episode' AND item_kind = 'episode' AND parent_id IS NOT NULL)
    OR
    -- Filler items don't need parents
    (item_kind = 'filler')
    OR 
    -- Non-episodes may or may not have parents
    (media_type != 'episode')
);