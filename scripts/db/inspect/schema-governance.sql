-- 只读数据库结构治理画像。
-- 用法：
--   psql "$DATABASE_URL" -f scripts/db/inspect/schema-governance.sql
--
-- 本脚本只读取 PostgreSQL catalog 与统计视图，不修改表、索引或统计信息。

\set ON_ERROR_STOP on
\pset pager off

\echo ''
\echo '== Schema summary =='
WITH table_counts AS (
    SELECT
        n.nspname AS schema_name,
        count(*) FILTER (WHERE c.relkind IN ('r', 'p')) AS physical_table_count,
        count(*) FILTER (WHERE c.relkind IN ('r', 'p') AND NOT c.relispartition) AS logical_table_count,
        count(*) FILTER (WHERE c.relkind = 'p') AS partitioned_table_count,
        count(*) FILTER (WHERE c.relispartition) AS partition_child_count
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname IN ('batch', 'archive', 'biz')
    GROUP BY n.nspname
),
index_counts AS (
    SELECT schemaname AS schema_name, count(*) AS index_count
    FROM pg_indexes
    WHERE schemaname IN ('batch', 'archive', 'biz')
    GROUP BY schemaname
)
SELECT
    t.schema_name,
    t.logical_table_count,
    t.physical_table_count,
    t.partitioned_table_count,
    t.partition_child_count,
    COALESCE(i.index_count, 0) AS index_count
FROM table_counts t
LEFT JOIN index_counts i USING (schema_name)
ORDER BY t.schema_name;

\echo ''
\echo '== Flyway state =='
SELECT
    count(*) FILTER (WHERE success) AS success_count,
    count(*) FILTER (WHERE NOT success) AS failed_count,
    max(installed_rank) AS max_installed_rank,
    max(version::integer) FILTER (WHERE success AND version ~ '^[0-9]+$') AS max_success_version
FROM batch.flyway_schema_history;

\echo ''
\echo '== Largest relations =='
SELECT
    n.nspname AS schema_name,
    c.relname AS relation_name,
    CASE
        WHEN c.relkind = 'p' THEN 'partitioned'
        WHEN c.relispartition THEN 'partition'
        ELSE 'table'
    END AS relation_kind,
    pg_size_pretty(pg_total_relation_size(c.oid)) AS total_size,
    pg_size_pretty(pg_relation_size(c.oid)) AS table_size,
    pg_size_pretty(pg_indexes_size(c.oid)) AS index_size,
    COALESCE(s.n_live_tup, 0) AS estimated_live_rows,
    COALESCE(s.n_dead_tup, 0) AS estimated_dead_rows,
    s.last_autovacuum,
    s.last_autoanalyze
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
LEFT JOIN pg_stat_user_tables s
       ON s.schemaname = n.nspname
      AND s.relname = c.relname
WHERE n.nspname IN ('batch', 'archive', 'biz')
  AND c.relkind IN ('r', 'p')
ORDER BY pg_total_relation_size(c.oid) DESC, n.nspname, c.relname
LIMIT 40;

\echo ''
\echo '== Partition default usage =='
WITH partitioned AS (
    SELECT
        parent_ns.nspname AS schema_name,
        parent.relname AS parent_table,
        child.relname AS default_partition,
        child.oid AS default_oid
    FROM pg_inherits i
    JOIN pg_class parent ON parent.oid = i.inhparent
    JOIN pg_namespace parent_ns ON parent_ns.oid = parent.relnamespace
    JOIN pg_class child ON child.oid = i.inhrelid
    WHERE parent_ns.nspname IN ('batch', 'archive', 'biz')
      AND child.relname LIKE '%_default'
)
SELECT
    schema_name,
    parent_table,
    default_partition,
    COALESCE(s.n_live_tup, 0) AS estimated_rows,
    pg_size_pretty(pg_total_relation_size(default_oid)) AS total_size
FROM partitioned p
LEFT JOIN pg_stat_user_tables s
       ON s.schemaname = p.schema_name
      AND s.relname = p.default_partition
ORDER BY estimated_rows DESC, schema_name, parent_table;

\echo ''
\echo '== Partition horizon =='
SELECT
    parent_ns.nspname AS schema_name,
    parent.relname AS parent_table,
    count(*) AS partition_count,
    min(child.relname) AS first_partition,
    max(child.relname) AS last_partition
FROM pg_inherits i
JOIN pg_class parent ON parent.oid = i.inhparent
JOIN pg_namespace parent_ns ON parent_ns.oid = parent.relnamespace
JOIN pg_class child ON child.oid = i.inhrelid
WHERE parent_ns.nspname IN ('batch', 'archive', 'biz')
  AND parent.relkind = 'p'
  AND child.relkind IN ('r', 'p')
GROUP BY parent_ns.nspname, parent.relname
ORDER BY parent_ns.nspname, parent.relname;

\echo ''
\echo '== Low usage indexes, evidence only =='
SELECT
    s.schemaname AS schema_name,
    s.relname AS table_name,
    s.indexrelname AS index_name,
    s.idx_scan,
    pg_size_pretty(pg_relation_size(s.indexrelid)) AS index_size,
    pg_get_indexdef(s.indexrelid) AS index_def
FROM pg_stat_user_indexes s
JOIN pg_index i ON i.indexrelid = s.indexrelid
WHERE s.schemaname IN ('batch', 'archive', 'biz')
  AND s.idx_scan = 0
  AND NOT i.indisprimary
  AND NOT i.indisunique
ORDER BY pg_relation_size(s.indexrelid) DESC, s.schemaname, s.relname, s.indexrelname
LIMIT 80;

\echo ''
\echo '== Same-table duplicate index definitions, evidence only =='
WITH normalized AS (
    SELECT
        s.schemaname,
        s.relname,
        s.indexrelname,
        regexp_replace(pg_get_indexdef(s.indexrelid), '^CREATE (UNIQUE )?INDEX [^ ]+ ', 'CREATE \1INDEX ', '') AS normalized_def,
        pg_relation_size(s.indexrelid) AS index_bytes
    FROM pg_stat_user_indexes s
    WHERE s.schemaname IN ('batch', 'archive', 'biz')
)
SELECT
    schemaname AS schema_name,
    relname AS table_name,
    array_agg(indexrelname ORDER BY indexrelname) AS index_names,
    count(*) AS duplicate_count,
    pg_size_pretty(sum(index_bytes)) AS total_index_size,
    normalized_def
FROM normalized
GROUP BY schemaname, relname, normalized_def
HAVING count(*) > 1
ORDER BY sum(index_bytes) DESC, schemaname, relname
LIMIT 40;

\echo ''
\echo '== B-tree left-prefix index candidates, evidence only =='
WITH idx AS (
    SELECT
        s.schemaname,
        s.relname,
        s.indexrelname,
        s.indexrelid,
        s.idx_scan,
        i.indisprimary,
        i.indisunique,
        i.indkey::int[] AS indkey,
        pg_relation_size(s.indexrelid) AS index_bytes,
        pg_get_indexdef(s.indexrelid) AS index_def
    FROM pg_stat_user_indexes s
    JOIN pg_index i ON i.indexrelid = s.indexrelid
    JOIN pg_class ic ON ic.oid = s.indexrelid
    JOIN pg_am am ON am.oid = ic.relam
    WHERE s.schemaname IN ('batch', 'archive', 'biz')
      AND am.amname = 'btree'
      AND i.indisvalid
      AND i.indisready
      AND array_length(i.indkey::int[], 1) IS NOT NULL
)
SELECT
    small.schemaname AS schema_name,
    small.relname AS table_name,
    small.indexrelname AS narrower_index,
    large.indexrelname AS wider_index,
    small.idx_scan AS narrower_scan,
    large.idx_scan AS wider_scan,
    pg_size_pretty(small.index_bytes) AS narrower_size,
    pg_size_pretty(large.index_bytes) AS wider_size,
    small.index_def AS narrower_def,
    large.index_def AS wider_def
FROM idx small
JOIN idx large
  ON large.schemaname = small.schemaname
 AND large.relname = small.relname
 AND large.indexrelid <> small.indexrelid
 AND array_length(large.indkey, 1) > array_length(small.indkey, 1)
 AND large.indkey[1:array_length(small.indkey, 1)] = small.indkey
WHERE NOT small.indisprimary
  AND NOT small.indisunique
ORDER BY small.index_bytes DESC, small.schemaname, small.relname, small.indexrelname
LIMIT 80;

\echo ''
\echo '== Archive table inventory =='
SELECT
    a.table_schema AS archive_schema,
    a.table_name AS archive_table,
    CASE
        WHEN b.table_name IS NULL THEN 'NO_MATCHING_HOT_TABLE'
        ELSE 'MATCHING_HOT_TABLE'
    END AS hot_table_match
FROM information_schema.tables a
LEFT JOIN information_schema.tables b
       ON b.table_schema = 'batch'
      AND b.table_name = regexp_replace(a.table_name, '_archive$', '')
WHERE a.table_schema = 'archive'
  AND a.table_type = 'BASE TABLE'
ORDER BY hot_table_match DESC, a.table_name;

\echo ''
\echo '== Archive policy inventory =='
SELECT
    table_schema,
    table_name,
    pg_size_pretty(pg_total_relation_size(format('%I.%I', table_schema, table_name)::regclass)) AS total_size
FROM information_schema.tables
WHERE table_schema = 'batch'
  AND table_name = 'archive_policy';

\echo ''
\echo '== Governance note =='
SELECT '仅凭本地 idx_scan=0 或低行数不能删除索引；DROP 候选必须结合 staging/prod 统计、查询计划和回滚窗口。' AS note;
