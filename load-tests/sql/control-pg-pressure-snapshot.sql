SELECT 'sampled_at' AS metric, clock_timestamp()::text AS value
UNION ALL
SELECT 'database_size_bytes', pg_database_size(current_database())::text
UNION ALL
SELECT 'active_connections', count(*) FILTER (WHERE state = 'active')::text
FROM pg_stat_activity
WHERE datname = current_database()
UNION ALL
SELECT 'active_waiting_connections',
       count(*) FILTER (WHERE state = 'active' AND wait_event IS NOT NULL)::text
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
FROM pg_stat_wal
UNION ALL
SELECT 'wal_records', coalesce(wal_records, 0)::text
FROM pg_stat_wal
UNION ALL
SELECT 'wal_fpi', coalesce(wal_fpi, 0)::text
FROM pg_stat_wal
UNION ALL
SELECT 'wal_buffers_full', coalesce(wal_buffers_full, 0)::text
FROM pg_stat_wal
UNION ALL
SELECT 'wal_write', coalesce(wal_write, 0)::text
FROM pg_stat_wal
UNION ALL
SELECT 'wal_sync', coalesce(wal_sync, 0)::text
FROM pg_stat_wal
UNION ALL
SELECT 'checkpoints_timed', coalesce(num_timed, 0)::text
FROM pg_stat_checkpointer
UNION ALL
SELECT 'checkpoints_requested', coalesce(num_requested, 0)::text
FROM pg_stat_checkpointer
UNION ALL
SELECT 'checkpoint_write_time_ms', coalesce(write_time, 0)::text
FROM pg_stat_checkpointer
UNION ALL
SELECT 'checkpoint_sync_time_ms', coalesce(sync_time, 0)::text
FROM pg_stat_checkpointer
UNION ALL
SELECT 'checkpoint_buffers_written', coalesce(buffers_written, 0)::text
FROM pg_stat_checkpointer;
