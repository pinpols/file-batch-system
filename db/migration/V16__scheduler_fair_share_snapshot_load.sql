-- =========================================================
-- V15 - Add scheduler fair-share snapshot and load fields
-- Notes:
-- 1) Extend quota / queue / worker tables with burst and fair-share controls.
-- 2) Persist tenant-level scheduler snapshot for audit and tuning.
-- 3) Keep the snapshot queryable by tenant and capture time.
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
