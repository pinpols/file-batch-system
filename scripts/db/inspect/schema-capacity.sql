-- Read-only schema capacity inspection for Docker and host psql environments.
-- Usage: psql "$DATABASE_URL" -f scripts/db/inspect/schema-capacity.sql

\set ON_ERROR_STOP on

-- Hot tables and their default partitions. A non-zero default partition count
-- means the partition window or routing policy needs attention.
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

-- Large hot tables, state distribution, and oldest row timestamp.
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

-- Payload distribution is intentionally sampled through PostgreSQL statistics;
-- do not scan payload_json in a request path.
SELECT
    count(*) AS rows_with_payload,
    avg(pg_column_size(payload_json)) AS avg_payload_bytes,
    max(pg_column_size(payload_json)) AS max_payload_bytes
FROM batch.result_version
WHERE payload_json IS NOT NULL;
