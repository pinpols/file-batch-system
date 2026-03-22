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
