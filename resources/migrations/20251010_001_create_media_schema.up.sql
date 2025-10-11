-- Ccreate core tables

CREATE TABLE library (
  id         VARCHAR(128) PRIMARY KEY,
  name       VARCHAR(128) UNIQUE NOT NULL CHECK (name <> ''),
  created_at timestamptz NOT NULL DEFAULT NOW(),
  updated_at timestamptz NOT NULL DEFAULT NOW()
);

CREATE TABLE media (
  id                VARCHAR(128) PRIMARY KEY,
  library_id        VARCHAR(128) NOT NULL REFERENCES library(id) ON DELETE CASCADE,
  name              VARCHAR(512) NOT NULL CHECK (name <> ''),
  overview          TEXT,
  community_rating  NUMERIC(3,1) CHECK (community_rating BETWEEN 0 AND 10),
  critic_rating     NUMERIC(3,1) CHECK (critic_rating BETWEEN 0 AND 10),
  rating            VARCHAR(10),
  media_type        VARCHAR(15) NOT NULL CHECK (media_type in ('movie', 'series', 'episode', 'season', 'filler')),
  production_year   INT NOT NULL CHECK (production_year >= 1800 AND production_year <= extract(year from now())::INT + 1),
  subtitles         BOOLEAN NOT NULL DEFAULT false,
  kid_friendly      BOOLEAN NOT NULL DEFAULT false,
  premiere          DATE NOT NULL,
  created_at        timestamptz NOT NULL DEFAULT NOW(),
  updated_at        timestamptz NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_media_library ON media(library_id);

CREATE TABLE genre (
  name        VARCHAR(128) PRIMARY KEY CHECK (name <> ''),
  description TEXT,
  created_at  timestamptz NOT NULL DEFAULT NOW(),
  updated_at  timestamptz NOT NULL DEFAULT NOW()
);

CREATE TABLE tag (
  name        VARCHAR(128) PRIMARY KEY CHECK (name <> ''),
  description TEXT,
  created_at  timestamptz NOT NULL DEFAULT NOW(),
  updated_at  timestamptz NOT NULL DEFAULT NOW()
);

CREATE TABLE channel (
  name        VARCHAR(128) PRIMARY KEY CHECK (name <> ''),
  description TEXT NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT NOW(),
  updated_at  timestamptz NOT NULL DEFAULT NOW()
);

-- Link tables

CREATE TABLE media_genres (
  media_id   VARCHAR(128) NOT NULL REFERENCES media(id) ON DELETE CASCADE,
  genre      VARCHAR(128) NOT NULL REFERENCES genre(name) ON DELETE CASCADE ON UPDATE CASCADE,
  created_at timestamptz NOT NULL DEFAULT NOW(),
  PRIMARY KEY (media_id, genre)
);

CREATE INDEX idx_media_genres_media ON media_genres(media_id);
CREATE INDEX idx_media_genres_genre ON media_genres(genre);

CREATE TABLE media_tags (
  media_id   VARCHAR(128) NOT NULL REFERENCES media(id) ON DELETE CASCADE,
  tag        VARCHAR(128) NOT NULL REFERENCES tag(name) ON DELETE CASCADE ON UPDATE CASCADE,
  created_at timestamptz NOT NULL DEFAULT NOW(),
  PRIMARY KEY (media_id, tag)
);

CREATE INDEX idx_media_tags_media ON media_tags(media_id);
CREATE INDEX idx_media_tags_tag ON media_tags(tag);

CREATE TABLE media_channels (
  media_id   VARCHAR(128) NOT NULL REFERENCES media(id) ON DELETE CASCADE,
  channel    VARCHAR(128) NOT NULL REFERENCES channel(name) ON DELETE CASCADE ON UPDATE CASCADE,
  created_at timestamptz NOT NULL DEFAULT NOW(),
  PRIMARY KEY (media_id, channel)
);

CREATE INDEX idx_media_channels_media ON media_channels(media_id);
CREATE INDEX idx_media_channels_channel ON media_channels(channel);

CREATE TABLE media_taglines (
  id BIGSERIAL PRIMARY KEY,
  media_id     VARCHAR(128) NOT NULL REFERENCES media(id) ON DELETE CASCADE,
  tagline      TEXT NOT NULL CHECK (length(trim(tagline)) > 0),
  created_at   timestamptz NOT NULL DEFAULT NOW(),
  UNIQUE (media_id, tagline)
);

-- Updated-at trigger fuction and triggers

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS trg_library_updated_at ON library;
CREATE TRIGGER trg_library_updated_at
BEFORE UPDATE ON library
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_media_updated_at ON media;
CREATE TRIGGER trg_media_updated_at
BEFORE UPDATE ON media
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_genre_updated_at ON genre;
CREATE TRIGGER trg_genre_updated_at
BEFORE UPDATE ON genre
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_tag_updated_at ON tag;
CREATE TRIGGER trg_tag_updated_at
BEFORE UPDATE ON tag
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_channel_updated_at ON channel;
CREATE TRIGGER trg_channel_updated_at
BEFORE UPDATE ON channel
FOR EACH ROW EXECUTE FUNCTION set_updated_at();
