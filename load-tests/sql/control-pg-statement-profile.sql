-- 仅展示当前平台库的业务 SQL；截断并压平 SQL 文本，保证报告可读。
SELECT left(regexp_replace(query, '\s+', ' ', 'g'), 240) AS query_sample,
       calls,
       round(total_exec_time::numeric, 3) AS total_exec_ms,
       round(mean_exec_time::numeric, 3) AS mean_exec_ms,
       rows,
       shared_blks_hit,
       shared_blks_read,
       shared_blks_dirtied,
       shared_blks_written,
       round(shared_blk_read_time::numeric, 3) AS blk_read_ms,
       round(shared_blk_write_time::numeric, 3) AS blk_write_ms,
       temp_blks_written,
       wal_records,
       wal_fpi,
       wal_bytes
FROM pg_stat_statements
WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
  AND query NOT ILIKE '%pg_stat_statements%'
ORDER BY total_exec_time DESC
LIMIT 30;

-- 累计执行时间和 WAL 不是同一维度，单独列出 WAL 写入最大的语句，避免遗漏低延迟高放大的写路径。
SELECT left(regexp_replace(query, '\s+', ' ', 'g'), 240) AS query_sample,
       calls,
       round(total_exec_time::numeric, 3) AS total_exec_ms,
       rows,
       wal_records,
       wal_fpi,
       wal_bytes,
       shared_blks_dirtied,
       shared_blks_written,
       round(shared_blk_read_time::numeric, 3) AS blk_read_ms,
       round(shared_blk_write_time::numeric, 3) AS blk_write_ms
FROM pg_stat_statements
WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
  AND query NOT ILIKE '%pg_stat_statements%'
ORDER BY wal_bytes DESC NULLS LAST
LIMIT 30;
