-- Allow library ids to be updated in place (library sync keys on name and
-- refreshes the id reported by Pseudovision); media rows must follow.

ALTER TABLE media
  DROP CONSTRAINT media_library_id_fkey;

--;;

ALTER TABLE media
  ADD CONSTRAINT media_library_id_fkey
  FOREIGN KEY (library_id) REFERENCES library(id)
  ON DELETE CASCADE ON UPDATE CASCADE;
