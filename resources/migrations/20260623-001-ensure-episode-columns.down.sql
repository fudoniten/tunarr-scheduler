-- Down migration is intentionally minimal: we don't drop columns because
-- they may have been added by the 20260218-001 migration. Dropping them
-- here would break that migration's down path.

--;;
