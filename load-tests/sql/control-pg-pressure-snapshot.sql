SELECT 'sampled_at' AS metric, clock_timestamp()::text AS value
UNION ALL
SELECT 'database_size_bytes', pg_database_size(current_database())::text
UNION ALL
SELECT 'active_connections', count(*) FILTER (WHERE state = 'active')::text
FROM pg_stat_activity
WHERE datname = current_database()
UNION ALL
SELECT 'waiting_connections', count(*) FILTER (WHERE wait_event IS NOT NULL)::text
FROM pg_stat_activity
WHERE datname = current_database()
UNION ALL
SELECT 'lock_waiters', count(*)::text
FROM pg_stat_activity
WHERE datname = current_database()
  AND wait_event_type = 'Lock'
UNION ALL
SELECT 'xact_commit', xact_commit::text
FROM pg_stat_database
WHERE datname = current_database()
UNION ALL
SELECT 'xact_rollback', xact_rollback::text
FROM pg_stat_database
WHERE datname = current_database()
UNION ALL
SELECT 'wal_bytes', coalesce(wal_bytes, 0)::text
FROM pg_stat_wal;
