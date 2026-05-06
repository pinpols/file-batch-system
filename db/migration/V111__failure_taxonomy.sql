-- =====================================================================
-- V111: ADR-012 失败分类（Failure Taxonomy）
-- =====================================================================
-- 背景:
--   §14.3.3 P1 — `job_instance.instance_status=FAILED` 是单一终态,
--   失败响应一刀切（global retry_policy）。真实场景 INFRASTRUCTURE
--   / DATA_QUALITY / BUSINESS_RULE / CONFIG / UPSTREAM_DELAY / TIMEOUT
--   / UNKNOWN 7 类失败响应完全不同。
--
-- 决策（ADR-012 §决策）:
--   - job_instance / job_task 各加 failure_class 列（nullable, 终态时填）;
--   - job_definition 加 retry_policy_by_class JSONB 列, NULL 用全局 retry_policy;
--   - check 约束限制取值; archive 镜像由 LIKE INCLUDING ALL 拷过去, 但
--     ALTER 语句必须双写（参见 §archive 冷表对齐红线）。
--
-- 兼容:
--   全部 NULL default; 老数据视为 UNKNOWN (代码侧解释), 不强制 backfill.
-- =====================================================================

ALTER TABLE batch.job_instance
    ADD COLUMN IF NOT EXISTS failure_class VARCHAR(32);

ALTER TABLE batch.job_task
    ADD COLUMN IF NOT EXISTS failure_class VARCHAR(32);

ALTER TABLE batch.job_definition
    ADD COLUMN IF NOT EXISTS retry_policy_by_class JSONB;

ALTER TABLE batch.job_instance DROP CONSTRAINT IF EXISTS ck_job_instance_failure_class;
ALTER TABLE batch.job_instance ADD CONSTRAINT ck_job_instance_failure_class
    CHECK (failure_class IS NULL OR failure_class IN (
        'INFRASTRUCTURE', 'DATA_QUALITY', 'BUSINESS_RULE',
        'CONFIG', 'UPSTREAM_DELAY', 'TIMEOUT', 'UNKNOWN'
    ));

ALTER TABLE batch.job_task DROP CONSTRAINT IF EXISTS ck_job_task_failure_class;
ALTER TABLE batch.job_task ADD CONSTRAINT ck_job_task_failure_class
    CHECK (failure_class IS NULL OR failure_class IN (
        'INFRASTRUCTURE', 'DATA_QUALITY', 'BUSINESS_RULE',
        'CONFIG', 'UPSTREAM_DELAY', 'TIMEOUT', 'UNKNOWN'
    ));

-- archive 镜像同步 (§archive 冷表对齐红线):
ALTER TABLE archive.job_instance_archive
    ADD COLUMN IF NOT EXISTS failure_class VARCHAR(32);

ALTER TABLE archive.job_task_archive
    ADD COLUMN IF NOT EXISTS failure_class VARCHAR(32);

-- 监控 / 失败聚类查询用部分索引 (只索引非 NULL 行, 减少 hot 表负担)
CREATE INDEX IF NOT EXISTS idx_job_instance_failure_class
    ON batch.job_instance (tenant_id, failure_class, finished_at DESC)
    WHERE failure_class IS NOT NULL;

COMMENT ON COLUMN batch.job_instance.failure_class IS
    'ADR-012 失败分类: INFRASTRUCTURE / DATA_QUALITY / BUSINESS_RULE / CONFIG / UPSTREAM_DELAY / TIMEOUT / UNKNOWN';
COMMENT ON COLUMN batch.job_task.failure_class IS
    'ADR-012 task 级失败分类; instance 级可由 task 聚合或独立判定';
COMMENT ON COLUMN batch.job_definition.retry_policy_by_class IS
    'ADR-012 按 class 重试策略 JSONB; 例: {"INFRASTRUCTURE":{"strategy":"EXPONENTIAL","maxAttempts":5}}; NULL = 用全局 retry_policy';
