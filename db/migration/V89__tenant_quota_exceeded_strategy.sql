-- V89: tenant_quota_policy.exceeded_strategy
-- 超额降级策略（quota 触顶时的行为）：
--   REJECT            — 立即 fail-fast，launch 抛 BizException（默认，向后兼容旧调用方期望"不静默积压"）
--   QUEUE_DEFER       — partition 留在 WAITING，下 tick 重派（V89 之前的隐式行为，兼容旧 partition_lifecycle）
--   DEGRADE_PRIORITY  — 仍 defer 但 ResourceScheduler 把决策 priority 降到 1 / band 降到 LOW，
--                        WaitingPartitionDispatchScheduler 的 fairness 排序自然把它沉到队尾
ALTER TABLE batch.tenant_quota_policy
    ADD COLUMN IF NOT EXISTS exceeded_strategy VARCHAR(32) NOT NULL DEFAULT 'REJECT';

ALTER TABLE batch.tenant_quota_policy
    DROP CONSTRAINT IF EXISTS ck_tenant_quota_exceeded_strategy;

ALTER TABLE batch.tenant_quota_policy
    ADD CONSTRAINT ck_tenant_quota_exceeded_strategy
    CHECK (exceeded_strategy IN ('REJECT', 'QUEUE_DEFER', 'DEGRADE_PRIORITY'));

COMMENT ON COLUMN batch.tenant_quota_policy.exceeded_strategy IS
    'V89: quota 触顶处置策略 — REJECT|QUEUE_DEFER|DEGRADE_PRIORITY';
