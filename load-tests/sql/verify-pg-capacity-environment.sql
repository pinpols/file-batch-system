-- 返回容量压测启动前必须匹配的 PostgreSQL 运行参数。
SELECT coalesce(current_setting('pg_stat_statements.track', true), 'unavailable'),
       current_setting('track_io_timing'),
       current_setting('synchronous_commit'),
       current_setting('wal_compression'),
       pg_size_bytes(current_setting('max_wal_size')),
       extract(epoch FROM current_setting('checkpoint_timeout')::interval)::bigint;
