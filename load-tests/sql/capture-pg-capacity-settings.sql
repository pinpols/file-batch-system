-- 记录容量压测报告中的 PostgreSQL 运行参数。
SELECT coalesce(current_setting('pg_stat_statements.track', true), 'unavailable'),
       current_setting('track_io_timing'),
       current_setting('synchronous_commit'),
       current_setting('wal_compression'),
       current_setting('max_wal_size'),
       current_setting('checkpoint_timeout');
