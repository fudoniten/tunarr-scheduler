-- Add media categorization

CREATE TABLE media_categorization (
  media_id VARCHAR(128) REFERENCES media(id) ON DELETE CASCADE,
  category VARCHAR(128) NOT NULL,
  category_value VARCHAR(128) NOT NULL,
  PRIMARY KEY (media_id, category, category_value)
);

--;;
