CREATE SCHEMA IF NOT EXISTS batch;

CREATE TABLE IF NOT EXISTS batch.worker_registry (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    worker_code         VARCHAR(128) NOT NULL,
    worker_group        VARCHAR(128) NOT NULL,
    host_name           VARCHAR(256),
    host_ip             VARCHAR(64),
    process_id          VARCHAR(64),
    capability_tags     JSONB,
    resource_tag        VARCHAR(64),
    status              VARCHAR(32)  NOT NULL DEFAULT 'ONLINE',
    heartbeat_at        TIMESTAMPTZ  NOT NULL,
    current_load        INTEGER      NOT NULL DEFAULT 0,
    drain_started_at    TIMESTAMPTZ,
    drain_deadline_at   TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_worker_registry_tenant_worker UNIQUE (tenant_id, worker_code),
    CONSTRAINT ck_worker_registry_status CHECK (status IN ('ONLINE', 'OFFLINE', 'DRAINING', 'DECOMMISSIONED'))
);

CREATE TABLE IF NOT EXISTS batch.quota_runtime_state (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    quota_scope         VARCHAR(64)  NOT NULL,
    owner_code          VARCHAR(128) NOT NULL,
    quota_reset_policy  VARCHAR(32)  NOT NULL DEFAULT 'NONE',
    window_started_at   TIMESTAMPTZ,
    window_expires_at   TIMESTAMPTZ,
    peak_borrowed_count INTEGER      NOT NULL DEFAULT 0,
    last_reset_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_quota_runtime_state UNIQUE (tenant_id, quota_scope, owner_code),
    CONSTRAINT ck_quota_runtime_state_policy CHECK (quota_reset_policy IN ('NONE', 'CALENDAR_DAY', 'SLIDING_WINDOW')),
    CONSTRAINT ck_quota_runtime_state_peak CHECK (peak_borrowed_count >= 0)
);

ALTER TABLE batch.job_instance
    ADD COLUMN IF NOT EXISTS deadline_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS expected_duration_seconds INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS sla_alerted_at TIMESTAMPTZ;

ALTER TABLE batch.worker_registry
    ADD COLUMN IF NOT EXISTS current_load INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS drain_started_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS drain_deadline_at TIMESTAMPTZ;

-- 与 docs/sql/flyway/V31__add_batch_day_support.sql 中 business_calendar 增量语义一致。
ALTER TABLE batch.business_calendar
    ADD COLUMN IF NOT EXISTS cutoff_time TIME NOT NULL DEFAULT TIME '06:00:00',
    ADD COLUMN IF NOT EXISTS late_arrival_tolerance_min INTEGER NOT NULL DEFAULT 60,
    ADD COLUMN IF NOT EXISTS sla_offset_min INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN batch.business_calendar.cutoff_time IS
    '批量日切换时间。在该时间之前触发的批次，biz_date 归属前一个业务日。';
COMMENT ON COLUMN batch.business_calendar.late_arrival_tolerance_min IS
    'cutoff 之后的容忍窗口（分钟），用于接收晚到数据。';
COMMENT ON COLUMN batch.business_calendar.sla_offset_min IS
    '批量日 SLA deadline = cutoff_time + sla_offset_min。';

ALTER TABLE batch.business_calendar DROP CONSTRAINT IF EXISTS ck_business_calendar_late_arrival_tolerance_min;
ALTER TABLE batch.business_calendar ADD CONSTRAINT ck_business_calendar_late_arrival_tolerance_min CHECK (late_arrival_tolerance_min >= 0);

ALTER TABLE batch.business_calendar DROP CONSTRAINT IF EXISTS ck_business_calendar_sla_offset_min;
ALTER TABLE batch.business_calendar ADD CONSTRAINT ck_business_calendar_sla_offset_min CHECK (sla_offset_min >= 0);

CREATE TABLE IF NOT EXISTS batch.batch_day_instance (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    calendar_code VARCHAR(128) NOT NULL,
    biz_date DATE NOT NULL,
    day_status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    open_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cutoff_at TIMESTAMPTZ,
    settled_at TIMESTAMPTZ,
    sla_deadline_at TIMESTAMPTZ,
    late_count INTEGER NOT NULL DEFAULT 0,
    catchup_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_batch_day_instance UNIQUE (tenant_id, calendar_code, biz_date),
    CONSTRAINT ck_batch_day_instance_status CHECK (day_status IN ('OPEN', 'CUTOFF', 'IN_FLIGHT', 'SETTLED', 'FAILED')),
    CONSTRAINT ck_batch_day_instance_late_count CHECK (late_count >= 0),
    CONSTRAINT ck_batch_day_instance_catchup_count CHECK (catchup_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_batch_day_instance_status
    ON batch.batch_day_instance (day_status, biz_date DESC);
