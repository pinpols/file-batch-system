SELECT clock_timestamp()::text,
       pg_database_size(current_database()),
       count(*) FILTER (WHERE state = 'active'),
       count(*) FILTER (WHERE state = 'active' AND wait_event IS NOT NULL),
       count(*) FILTER (WHERE wait_event_type = 'Lock'),
       (SELECT xact_commit FROM pg_stat_database WHERE datname = current_database()),
       (SELECT xact_rollback FROM pg_stat_database WHERE datname = current_database()),
       coalesce((SELECT wal_bytes FROM pg_stat_wal), 0),
       coalesce((SELECT wal_records FROM pg_stat_wal), 0),
       coalesce((SELECT wal_fpi FROM pg_stat_wal), 0),
       coalesce((SELECT wal_buffers_full FROM pg_stat_wal), 0),
       coalesce((SELECT wal_write FROM pg_stat_wal), 0),
       coalesce((SELECT wal_sync FROM pg_stat_wal), 0),
       coalesce((SELECT num_timed FROM pg_stat_checkpointer), 0),
       coalesce((SELECT num_requested FROM pg_stat_checkpointer), 0),
       coalesce((SELECT buffers_written FROM pg_stat_checkpointer), 0)
FROM pg_stat_activity
WHERE datname = current_database();
