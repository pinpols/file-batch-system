-- =========================================================
-- V15 - Add scheduler fair-share snapshot and load fields
-- 说明：
-- 1) 扩展配额、队列和 Worker 表，增加突发流量与公平调度控制项。
-- 2) 持久化租户级调度快照，供审计和调优使用。
-- 3) 支持按租户和采集时间查询快照。
-- =========================================================

ALTER TABLE batch.tenant_quota_policy
    ADD COLUMN IF NOT EXISTS fair_share_group VARCHAR(128),
    ADD COLUMN IF NOT EXISTS burst_limit INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS partition_burst_limit INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS quota_reset_policy VARCHAR(32) NOT NULL DEFAULT 'NONE',
    ADD COLUMN IF NOT EXISTS group_shared_max_running_jobs INTEGER NOT NULL DEFAULT 0;

ALTER TABLE batch.resource_queue
    ADD COLUMN IF NOT EXISTS fair_share_group VARCHAR(128),
    ADD COLUMN IF NOT EXISTS burst_limit INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS quota_reset_policy VARCHAR(32) NOT NULL DEFAULT 'NONE',
    ADD COLUMN IF NOT EXISTS group_shared_max_running_jobs INTEGER NOT NULL DEFAULT 0;

ALTER TABLE batch.worker_registry
    ADD COLUMN IF NOT EXISTS current_load INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS batch.tenant_scheduler_snapshot (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    snapshot_at              TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    fair_share_group         VARCHAR(128),
    policy_code              VARCHAR(128),
    active_jobs              INTEGER      NOT NULL DEFAULT 0,
    active_partitions        INTEGER      NOT NULL DEFAULT 0,
    max_jobs_base            INTEGER,
    burst_limit              INTEGER,
    effective_job_cap        INTEGER,
    group_active_jobs        INTEGER,
    group_max_jobs           INTEGER,
    quota_reset_policy       VARCHAR(32),
    online_workers           INTEGER,
    detail_json              JSONB
);

CREATE INDEX IF NOT EXISTS idx_tenant_scheduler_snapshot_tenant_time
    ON batch.tenant_scheduler_snapshot (tenant_id, snapshot_at DESC);
