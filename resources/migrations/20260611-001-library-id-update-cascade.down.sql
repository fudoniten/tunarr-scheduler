ALTER TABLE media
  DROP CONSTRAINT media_library_id_fkey;

--;;

ALTER TABLE media
  ADD CONSTRAINT media_library_id_fkey
  FOREIGN KEY (library_id) REFERENCES library(id)
  ON DELETE CASCADE;
