-- 生产保留治理计划只读查询；用于输出候选量和策略缺口，不执行归档、清理或维护命令。
WITH thresholds AS (
    SELECT
        :old_runtime_days::bigint AS old_runtime_days,
        :old_outbox_days::bigint AS old_outbox_days,
        :old_trigger_days::bigint AS old_trigger_days,
        :dedup_warn_rows::bigint AS dedup_warn_rows
),
archive_policy_summary AS (
    SELECT
        target_table,
        count(*) AS policy_rows,
        count(*) FILTER (WHERE archive_enabled) AS archive_enabled_rows,
        count(*) FILTER (WHERE cleanup_enabled) AS cleanup_enabled_rows,
        min(retention_days) AS min_retention_days
    FROM :"schema".archive_policy
    WHERE target_table IN (
        'job_instance',
        'workflow_run',
        'job_partition',
        'file_record',
        'audit_log',
        'outbox_event',
        'event_delivery_log',
        'trigger_outbox_event',
        'dead_letter_task',
        'job_execution_log'
    )
    GROUP BY target_table
),
required_policies AS (
    SELECT *
    FROM (VALUES
        ('job_instance'),
        ('workflow_run'),
        ('job_partition'),
        ('file_record'),
        ('audit_log'),
        ('outbox_event'),
        ('trigger_outbox_event'),
        ('dead_letter_task'),
        ('job_execution_log')
    ) AS required(target_table)
),
runtime_candidates AS (
    SELECT
        (SELECT count(*)
           FROM :"schema".job_instance
          WHERE instance_status IN (
              'PARTIAL_FAILED', 'SUCCESS', 'FAILED', 'CANCELLED', 'TERMINATED',
              'SUCCESS_DRY_RUN', 'FAILED_DRY_RUN'
          )
            AND coalesce(finished_at, created_at) < now() - (:old_runtime_days::bigint * interval '1 day')
        ) AS old_terminal_job_instances,
        (SELECT count(*)
           FROM :"schema".outbox_event
          WHERE publish_status = 'PUBLISHED'
            AND created_at < now() - (:old_outbox_days::bigint * interval '1 day')
        ) AS old_published_outbox_events,
        (SELECT count(*)
           FROM :"schema".outbox_event
          WHERE publish_status = 'GIVE_UP'
            AND created_at < now() - (:old_runtime_days::bigint * interval '1 day')
        ) AS old_giveup_outbox_events,
        (SELECT count(*)
           FROM :"schema".trigger_request
          WHERE request_status IN ('DUPLICATE', 'REJECTED', 'LAUNCHED', 'GIVE_UP')
            AND created_at < now() - (:old_trigger_days::bigint * interval '1 day')
        ) AS old_closed_trigger_requests,
        (SELECT count(*) FROM :"schema".outbox_event_dedup_key) AS outbox_dedup_rows,
        (SELECT count(*) FROM :"schema".job_instance_dedup_key) AS job_dedup_rows
),
policy_plan AS (
    SELECT
        required.target_table,
        coalesce(summary.policy_rows, 0) AS policy_rows,
        coalesce(summary.archive_enabled_rows, 0) AS archive_enabled_rows,
        coalesce(summary.cleanup_enabled_rows, 0) AS cleanup_enabled_rows,
        summary.min_retention_days
    FROM required_policies required
    LEFT JOIN archive_policy_summary summary
           ON summary.target_table = required.target_table
)
SELECT 'postgres.archive_policy' AS area,
       CASE
           WHEN policy_rows = 0 THEN 'WARN'
           WHEN archive_enabled_rows = 0 THEN 'PLAN'
           ELSE 'OK'
       END AS status,
       target_table AS metric,
       'policies=' || policy_rows || ', archive_enabled=' || archive_enabled_rows || ', cleanup_enabled=' || cleanup_enabled_rows AS value,
       'policy>=1, archive_enabled>=1' AS threshold,
       coalesce('min_retention_days=' || min_retention_days, '缺少 archive_policy') AS detail
FROM policy_plan
UNION ALL
SELECT 'postgres.retention_candidates' AS area,
       CASE WHEN old_terminal_job_instances > 0 THEN 'PLAN' ELSE 'OK' END AS status,
       'job_instance.terminal_old' AS metric,
       old_terminal_job_instances::text AS value,
       thresholds.old_runtime_days || ' days' AS threshold,
       '终态作业实例超过保留窗口，应进入归档或分区保留评审' AS detail
FROM runtime_candidates
CROSS JOIN thresholds
UNION ALL
SELECT 'postgres.retention_candidates' AS area,
       CASE WHEN old_published_outbox_events > 0 THEN 'PLAN' ELSE 'OK' END AS status,
       'outbox_event.PUBLISHED_old' AS metric,
       old_published_outbox_events::text AS value,
       thresholds.old_outbox_days || ' days' AS threshold,
       '已发布 Outbox 超过短保留窗口，应进入 Outbox 归档或清理流程' AS detail
FROM runtime_candidates
CROSS JOIN thresholds
UNION ALL
SELECT 'postgres.retention_candidates' AS area,
       CASE WHEN old_giveup_outbox_events > 0 THEN 'PLAN' ELSE 'OK' END AS status,
       'outbox_event.GIVE_UP_old' AS metric,
       old_giveup_outbox_events::text AS value,
       thresholds.old_runtime_days || ' days' AS threshold,
       '放弃事件超过保留窗口，应先保留事故证据再进入归档评审' AS detail
FROM runtime_candidates
CROSS JOIN thresholds
UNION ALL
SELECT 'postgres.retention_candidates' AS area,
       CASE WHEN old_closed_trigger_requests > 0 THEN 'PLAN' ELSE 'OK' END AS status,
       'trigger_request.closed_old' AS metric,
       old_closed_trigger_requests::text AS value,
       thresholds.old_trigger_days || ' days' AS threshold,
       '已闭环触发请求超过保留窗口，应检查归档和幂等证据保留口径' AS detail
FROM runtime_candidates
CROSS JOIN thresholds
UNION ALL
SELECT 'postgres.dedup_ledger' AS area,
       CASE WHEN outbox_dedup_rows >= thresholds.dedup_warn_rows THEN 'PLAN' ELSE 'OK' END AS status,
       'outbox_event_dedup_key.rows' AS metric,
       outbox_dedup_rows::text AS value,
       thresholds.dedup_warn_rows::text AS threshold,
       '幂等账本只增，达到阈值后按 dedup ledger 保留治理评审' AS detail
FROM runtime_candidates
CROSS JOIN thresholds
UNION ALL
SELECT 'postgres.dedup_ledger' AS area,
       CASE WHEN job_dedup_rows >= thresholds.dedup_warn_rows THEN 'PLAN' ELSE 'OK' END AS status,
       'job_instance_dedup_key.rows' AS metric,
       job_dedup_rows::text AS value,
       thresholds.dedup_warn_rows::text AS threshold,
       '幂等账本只增，达到阈值后按 dedup ledger 保留治理评审' AS detail
FROM runtime_candidates
CROSS JOIN thresholds
ORDER BY area, metric;
