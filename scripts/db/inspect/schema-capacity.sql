-- 只读检查数据库结构容量，支持 Docker 容器和宿主机上的 psql 环境。
-- 用法：psql "$DATABASE_URL" -f scripts/db/inspect/schema-capacity.sql

\set ON_ERROR_STOP on

-- 检查热表及其默认分区。默认分区记录数非零时，应检查分区窗口或路由策略。
WITH partitioned AS (
    SELECT
        n.nspname AS schema_name,
        c.relname AS parent_table,
        d.relname AS default_partition
    FROM pg_inherits i
    JOIN pg_class c ON c.oid = i.inhparent
    JOIN pg_namespace n ON n.oid = c.relnamespace
    JOIN pg_class d ON d.oid = i.inhrelid
    WHERE d.relname LIKE '%_default'
      AND n.nspname IN ('batch', 'batch_business')
)
SELECT
    schema_name,
    parent_table,
    default_partition,
    COALESCE((
        SELECT n_live_tup
        FROM pg_stat_user_tables s
        WHERE s.schemaname = partitioned.schema_name
          AND s.relname = partitioned.default_partition
    ), 0) AS estimated_rows,
    pg_size_pretty(
        COALESCE(pg_total_relation_size(format('%I.%I', schema_name, default_partition)), 0)
    ) AS total_size
FROM partitioned
ORDER BY estimated_rows DESC, schema_name, parent_table;

-- 检查大型热表、状态分布和最早记录时间。
SELECT
    n.nspname AS schema_name,
    c.relname AS table_name,
    pg_size_pretty(pg_total_relation_size(c.oid)) AS total_size,
    pg_size_pretty(pg_relation_size(c.oid)) AS table_size,
    pg_size_pretty(pg_indexes_size(c.oid)) AS index_size,
    s.n_live_tup,
    s.n_dead_tup,
    s.last_autovacuum,
    s.last_autoanalyze
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
LEFT JOIN pg_stat_user_tables s
       ON s.schemaname = n.nspname
      AND s.relname = c.relname
WHERE n.nspname IN ('batch', 'batch_business')
  AND c.relkind IN ('r', 'p')
ORDER BY pg_total_relation_size(c.oid) DESC
LIMIT 30;

SELECT
    status,
    count(*) AS row_count,
    min(updated_at) AS oldest_updated_at
FROM batch.result_version
GROUP BY status
ORDER BY status;

-- Payload 分布通过 PostgreSQL 统计信息抽样获取；请求链路中不要扫描 payload_json。
SELECT
    count(*) AS rows_with_payload,
    avg(pg_column_size(payload_json)) AS avg_payload_bytes,
    max(pg_column_size(payload_json)) AS max_payload_bytes
FROM batch.result_version
WHERE payload_json IS NOT NULL;
