-- Drop triggers first (optional: tables would drop them anyway)

DROP TRIGGER IF EXISTS trg_channel_updated_at ON channel;
--;;
DROP TRIGGER IF EXISTS trg_tag_updated_at ON tag;
--;;
DROP TRIGGER IF EXISTS trg_genre_updated_at ON genre;
--;;
DROP TRIGGER IF EXISTS trg_media_updated_at ON media;
--;;
DROP TRIGGER IF EXISTS trg_library_updated_at ON library;
--;;

-- Drop trigger function
DROP FUNCTION IF EXISTS set_updated_at();
--;;

-- Drop link tables (reverse dependency order)
DROP TABLE IF EXISTS media_taglines;
--;;
DROP TABLE IF EXISTS media_channels;
--;;
DROP TABLE IF EXISTS media_tags;
--;;
DROP TABLE IF EXISTS media_genres;
--;;

-- Drop vocab & core tables
DROP TABLE IF EXISTS channel;
--;;
DROP TABLE IF EXISTS tag;
--;;
DROP TABLE IF EXISTS genre;
--;;
DROP TABLE IF EXISTS media;
--;;
DROP TABLE IF EXISTS library;
--;;
