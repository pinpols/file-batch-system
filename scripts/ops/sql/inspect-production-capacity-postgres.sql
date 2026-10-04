-- 生产容量巡检只读查询；不要在此文件加入修复、清理或锁表语句。
WITH thresholds AS (
    SELECT
        (:table_size_warn_mb::bigint * 1024 * 1024) AS table_size_warn_bytes,
        :dead_tuple_warn_count::bigint AS dead_tuple_warn_count,
        :outbox_backlog_warn_count::bigint AS outbox_backlog_warn_count,
        :trigger_backlog_warn_count::bigint AS trigger_backlog_warn_count,
        :dedup_warn_count::bigint AS dedup_warn_count,
        :old_runtime_warn_days::bigint AS old_runtime_warn_days
),
database_size AS (
    SELECT pg_database_size(current_database()) AS size_bytes
),
top_relations AS (
    SELECT
        relid::regclass::text AS relation_name,
        pg_total_relation_size(relid) AS total_bytes,
        n_live_tup,
        n_dead_tup
    FROM pg_stat_user_tables
    WHERE schemaname IN (:'schema', 'archive')
    ORDER BY pg_total_relation_size(relid) DESC
    LIMIT 12
),
runtime_counts AS (
    SELECT
        (SELECT count(*) FROM :"schema".outbox_event
          WHERE publish_status IN ('NEW', 'FAILED', 'PUBLISHING')) AS outbox_backlog,
        (SELECT count(*) FROM :"schema".trigger_request
          WHERE request_status = 'ACCEPTED'
            AND related_job_instance_id IS NULL) AS trigger_accepted_backlog,
        (SELECT count(*) FROM :"schema".job_instance
          WHERE instance_status IN ('RUNNING', 'PENDING')) AS active_job_instances,
        (SELECT count(*) FROM :"schema".job_instance
          WHERE created_at < now() - (:old_runtime_warn_days::bigint * interval '1 day')) AS old_job_instances,
        (SELECT count(*) FROM :"schema".outbox_event
          WHERE created_at < now() - (:old_runtime_warn_days::bigint * interval '1 day')) AS old_outbox_events,
        (SELECT count(*) FROM :"schema".outbox_event_dedup_key) AS outbox_dedup_rows,
        (SELECT count(*) FROM :"schema".job_instance_dedup_key) AS job_dedup_rows
),
dead_tuple_summary AS (
    SELECT coalesce(sum(n_dead_tup), 0)::bigint AS dead_tuples
    FROM pg_stat_user_tables
    WHERE schemaname IN (:'schema', 'archive')
),
partition_summary AS (
    SELECT
        coalesce(count(*) FILTER (WHERE inhparent = (:'schema' || '.job_instance')::regclass), 0) AS job_instance_partitions,
        coalesce(count(*) FILTER (WHERE inhparent = (:'schema' || '.outbox_event')::regclass), 0) AS outbox_event_partitions
    FROM pg_inherits
    WHERE inhparent IN ((:'schema' || '.job_instance')::regclass, (:'schema' || '.outbox_event')::regclass)
)
SELECT 'postgres.database_size' AS check_name,
       'INFO' AS status,
       'current_database' AS metric,
       pg_size_pretty(size_bytes) AS value,
       '' AS threshold,
       '数据库逻辑大小；文件系统上的 PGDATA/WAL/膨胀可能明显更大' AS detail
FROM database_size
UNION ALL
SELECT 'postgres.relation_size' AS check_name,
       CASE WHEN total_bytes >= thresholds.table_size_warn_bytes THEN 'WARN' ELSE 'OK' END AS status,
       relation_name AS metric,
       pg_size_pretty(total_bytes) AS value,
       pg_size_pretty(thresholds.table_size_warn_bytes) AS threshold,
       'live=' || n_live_tup || ', dead=' || n_dead_tup AS detail
FROM top_relations
CROSS JOIN thresholds
UNION ALL
SELECT 'postgres.dead_tuples' AS check_name,
       CASE WHEN dead_tuples >= thresholds.dead_tuple_warn_count THEN 'WARN' ELSE 'OK' END AS status,
       'pg_stat_user_tables.n_dead_tup' AS metric,
       dead_tuples::text AS value,
       thresholds.dead_tuple_warn_count::text AS threshold,
       'dead tuple 高说明需要检查 autovacuum、长事务或清理节奏' AS detail
FROM dead_tuple_summary
CROSS JOIN thresholds
UNION ALL
SELECT 'runtime.outbox_backlog' AS check_name,
       CASE WHEN outbox_backlog >= thresholds.outbox_backlog_warn_count THEN 'WARN' ELSE 'OK' END AS status,
       'outbox_event.NEW_FAILED_PUBLISHING' AS metric,
       outbox_backlog::text AS value,
       thresholds.outbox_backlog_warn_count::text AS threshold,
       'Outbox 积压会放大表和 Kafka 延迟，应结合 relay 与 broker lag 排查' AS detail
FROM runtime_counts
CROSS JOIN thresholds
UNION ALL
SELECT 'runtime.trigger_accepted_backlog' AS check_name,
       CASE WHEN trigger_accepted_backlog >= thresholds.trigger_backlog_warn_count THEN 'WARN' ELSE 'OK' END AS status,
       'trigger_request.ACCEPTED_without_instance' AS metric,
       trigger_accepted_backlog::text AS value,
       thresholds.trigger_backlog_warn_count::text AS threshold,
       'Trigger 已接收但未关联实例，持续增长会形成控制面残留' AS detail
FROM runtime_counts
CROSS JOIN thresholds
UNION ALL
SELECT 'runtime.active_job_instances' AS check_name,
       'INFO' AS status,
       'job_instance.RUNNING_PENDING' AS metric,
       active_job_instances::text AS value,
       '' AS threshold,
       '活跃实例数量本身不是异常，但需要和 worker 饱和度、窗口 SLA 一起看' AS detail
FROM runtime_counts
UNION ALL
SELECT 'retention.old_job_instances' AS check_name,
       CASE WHEN old_job_instances > 0 THEN 'WARN' ELSE 'OK' END AS status,
       'job_instance.created_at' AS metric,
       old_job_instances::text AS value,
       thresholds.old_runtime_warn_days || ' days' AS threshold,
       '超过保留窗口仍在热表，检查归档调度、分区或保留策略' AS detail
FROM runtime_counts
CROSS JOIN thresholds
UNION ALL
SELECT 'retention.old_outbox_events' AS check_name,
       CASE WHEN old_outbox_events > 0 THEN 'WARN' ELSE 'OK' END AS status,
       'outbox_event.created_at' AS metric,
       old_outbox_events::text AS value,
       thresholds.old_runtime_warn_days || ' days' AS threshold,
       '超过保留窗口仍在热表，检查 Outbox 归档和失败事件处理' AS detail
FROM runtime_counts
CROSS JOIN thresholds
UNION ALL
SELECT 'retention.outbox_dedup_rows' AS check_name,
       CASE WHEN outbox_dedup_rows >= thresholds.dedup_warn_count THEN 'WARN' ELSE 'OK' END AS status,
       'outbox_event_dedup_key.rows' AS metric,
       outbox_dedup_rows::text AS value,
       thresholds.dedup_warn_count::text AS threshold,
       '幂等账本按设计只增，达到阈值后按 dedup ledger runbook 做归档评审' AS detail
FROM runtime_counts
CROSS JOIN thresholds
UNION ALL
SELECT 'retention.job_dedup_rows' AS check_name,
       CASE WHEN job_dedup_rows >= thresholds.dedup_warn_count THEN 'WARN' ELSE 'OK' END AS status,
       'job_instance_dedup_key.rows' AS metric,
       job_dedup_rows::text AS value,
       thresholds.dedup_warn_count::text AS threshold,
       '幂等账本按设计只增，达到阈值后按 dedup ledger runbook 做归档评审' AS detail
FROM runtime_counts
CROSS JOIN thresholds
UNION ALL
SELECT 'postgres.partitions' AS check_name,
       CASE WHEN job_instance_partitions = 0 OR outbox_event_partitions = 0 THEN 'WARN' ELSE 'OK' END AS status,
       'job_instance/outbox_event partitions' AS metric,
       job_instance_partitions || '/' || outbox_event_partitions AS value,
       '>0/>0' AS threshold,
       '核心运行表应保持分区可见；分区缺失时检查迁移和未来分区维护' AS detail
FROM partition_summary
ORDER BY check_name, metric;
