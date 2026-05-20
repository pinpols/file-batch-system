-- =========================================================
-- V140: archive.dead_letter_task_archive + archive_policy 白名单 + 默认策略种子
--
-- 依据: docs/analysis/dba-schema-review-2026-05-20.md §3.8 / Top10 §3.4
--
-- 背景:
--   V7 创建 batch.dead_letter_task 时未配套 archive 表和清理策略,
--   生产长期累积成事故复盘黑洞(GIVE_UP 状态行永不清理)。
--
-- 步骤:
--   1) 镜像建 archive.dead_letter_task_archive(LIKE INCLUDING ALL,与 V71 风格一致);
--   2) 补 PK;
--   3) 加 (replay_status, created_at) 复合索引(归档表事故查询主路径);
--   4) ALTER archive_policy CHECK,加入白名单;
--   5) 默认策略种子 — GIVE_UP 90 天归档(NEW 行属于"活跃可重放",cleanup 不动)。
-- =========================================================

CREATE TABLE IF NOT EXISTS archive.dead_letter_task_archive
    (LIKE batch.dead_letter_task INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING IDENTITY INCLUDING CONSTRAINTS);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE n.nspname = 'archive'
          AND t.relname = 'dead_letter_task_archive'
          AND c.contype = 'p'
    ) THEN
        ALTER TABLE archive.dead_letter_task_archive
            ADD CONSTRAINT pk_dead_letter_task_archive PRIMARY KEY (id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_dead_letter_task_archive_replay_status_created
    ON archive.dead_letter_task_archive (replay_status, created_at);

-- 加入 archive_policy 白名单(累积上一次 V139 的列表)
ALTER TABLE batch.archive_policy
    DROP CONSTRAINT ck_archive_policy_table;

ALTER TABLE batch.archive_policy
    ADD CONSTRAINT ck_archive_policy_table CHECK (target_table IN (
        'job_instance','workflow_run','job_partition','file_record',
        'audit_log','outbox_event','event_delivery_log','webhook_delivery_log',
        'console_operation_audit',
        'trigger_outbox_event',
        'dead_letter_task'
    ));

-- 默认种子: 90 天归档(GIVE_UP 行长期事故复盘价值高,先保留再清)。
INSERT INTO batch.archive_policy (
    tenant_id, target_table, retention_days,
    archive_enabled, cleanup_enabled, batch_size,
    description, created_by, updated_by
)
SELECT
    t.tenant_id,
    'dead_letter_task',
    90,
    TRUE,
    FALSE,
    500,
    '死信任务,GIVE_UP 行 90 天后允许归档;cleanup_enabled 由运维确认 90 天为合规边界后再开',
    'system',
    'system'
FROM batch.tenant t
WHERE t.status = 'ACTIVE'
  AND NOT EXISTS (
      SELECT 1 FROM batch.archive_policy p
      WHERE p.tenant_id = t.tenant_id
        AND p.target_table = 'dead_letter_task'
  );
