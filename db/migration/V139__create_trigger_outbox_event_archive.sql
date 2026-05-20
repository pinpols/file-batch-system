-- =========================================================
-- V139: archive.trigger_outbox_event_archive + archive_policy 白名单 + 默认策略种子
--
-- 依据: docs/analysis/dba-schema-review-2026-05-20.md §3.10 / Top10 §3.3
--
-- 背景:
--   V80 创建 batch.trigger_outbox_event 时未配套归档表与清理策略,
--   线上 ADR-010 全量后(trigger fire 每秒级)将无界增长。
--   本迁移补齐归档基础设施,relay 游标/状态机改造留 PR-B(代码层)。
--
-- 步骤:
--   1) 镜像建 archive.trigger_outbox_event_archive(LIKE INCLUDING ALL 风格,
--      与 V71 其它归档表一致);
--   2) 显式补 PK,与 V71 DO $$ ... END $$ 块保持一致风格;
--   3) 加典型读路径索引(归档表主要供事故复盘 SELECT,只需 created_at);
--   4) ALTER archive_policy CHECK,加入 trigger_outbox_event 白名单;
--   5) 给所有 ACTIVE 租户种子默认策略行(7d / 30d 与 outbox_event 对齐)。
--
-- 同时注册到 ArchiveSchemaDriftCheck.ARCHIVED_TABLES (代码侧 PR-B 同步)。
-- =========================================================

CREATE TABLE IF NOT EXISTS archive.trigger_outbox_event_archive
    (LIKE batch.trigger_outbox_event INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING IDENTITY INCLUDING CONSTRAINTS);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE n.nspname = 'archive'
          AND t.relname = 'trigger_outbox_event_archive'
          AND c.contype = 'p'
    ) THEN
        ALTER TABLE archive.trigger_outbox_event_archive
            ADD CONSTRAINT pk_trigger_outbox_event_archive PRIMARY KEY (id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_trigger_outbox_event_archive_created
    ON archive.trigger_outbox_event_archive (publish_status, created_at);

-- 加入 archive_policy 白名单
ALTER TABLE batch.archive_policy
    DROP CONSTRAINT ck_archive_policy_table;

ALTER TABLE batch.archive_policy
    ADD CONSTRAINT ck_archive_policy_table CHECK (target_table IN (
        'job_instance','workflow_run','job_partition','file_record',
        'audit_log','outbox_event','event_delivery_log','webhook_delivery_log',
        'console_operation_audit',
        'trigger_outbox_event'
    ));

-- 给所有 ACTIVE 租户默认种子(与 outbox_event 同节奏:PUBLISHED 7 天,默认开归档+清理)
INSERT INTO batch.archive_policy (
    tenant_id, target_table, retention_days,
    archive_enabled, cleanup_enabled, batch_size,
    description, created_by, updated_by
)
SELECT
    t.tenant_id,
    'trigger_outbox_event',
    7,
    TRUE,
    TRUE,
    1000,
    'ADR-010 trigger 异步事件,PUBLISHED 后保留 7 天供回查,GIVE_UP 由 cleanup 脚本独立控制',
    'system',
    'system'
FROM batch.tenant t
WHERE t.status = 'ACTIVE'
  AND NOT EXISTS (
      SELECT 1 FROM batch.archive_policy p
      WHERE p.tenant_id = t.tenant_id
        AND p.target_table = 'trigger_outbox_event'
  );
