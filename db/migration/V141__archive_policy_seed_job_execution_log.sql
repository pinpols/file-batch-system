-- =========================================================
-- V141: job_execution_log 加入 archive_policy 白名单 + 默认种子
--
-- 依据: docs/analysis/dba-schema-review-2026-05-20.md §6.2 / Top10 §3.x
--
-- 背景:
--   archive.job_execution_log_archive 已在 V71 建好,但 archive_policy 白名单 / 种子缺失,
--   所以归档 scheduler 跳过该表 → 长期累积"系统/业务/重试/告警/审计"日志,
--   单租户日级写入量可达百万行。补齐策略。
--
-- 加入 archive_policy 白名单(累积 V140 的列表)
-- =========================================================

ALTER TABLE batch.archive_policy
    DROP CONSTRAINT ck_archive_policy_table;

ALTER TABLE batch.archive_policy
    ADD CONSTRAINT ck_archive_policy_table CHECK (target_table IN (
        'job_instance','workflow_run','job_partition','file_record',
        'audit_log','outbox_event','event_delivery_log','webhook_delivery_log',
        'console_operation_audit',
        'trigger_outbox_event',
        'dead_letter_task',
        'job_execution_log'
    ));

-- 默认种子: 30 天保留(执行日志主要供"最近运行回查"价值,长期靠 archive 冷库)。
INSERT INTO batch.archive_policy (
    tenant_id, target_table, retention_days,
    archive_enabled, cleanup_enabled, batch_size,
    description, created_by, updated_by
)
SELECT
    t.tenant_id,
    'job_execution_log',
    30,
    TRUE,
    TRUE,
    2000,
    '作业执行日志(DEBUG/INFO/WARN/ERROR + SYSTEM/BUSINESS/RETRY/ALARM/AUDIT),30 天后归档+清理',
    'system',
    'system'
FROM batch.tenant t
WHERE t.status = 'ACTIVE'
  AND NOT EXISTS (
      SELECT 1 FROM batch.archive_policy p
      WHERE p.tenant_id = t.tenant_id
        AND p.target_table = 'job_execution_log'
  );
