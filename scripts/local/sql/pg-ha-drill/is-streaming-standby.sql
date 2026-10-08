SELECT pg_is_in_recovery()
  AND EXISTS (
    SELECT 1
    FROM pg_stat_wal_receiver
    WHERE status = 'streaming'
  );
