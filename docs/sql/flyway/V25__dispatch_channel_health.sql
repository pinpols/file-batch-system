-- =========================================================
-- V25 - Dispatch channel health state
-- =========================================================

CREATE TABLE IF NOT EXISTS batch.file_channel_health (
    id                     BIGSERIAL PRIMARY KEY,
    tenant_id              VARCHAR(64)  NOT NULL,
    channel_code           VARCHAR(128) NOT NULL,
    channel_type           VARCHAR(32)  NOT NULL,
    health_status          VARCHAR(32)  NOT NULL,
    consecutive_failures   INTEGER      NOT NULL DEFAULT 0,
    last_probe_at          TIMESTAMPTZ,
    last_success_at        TIMESTAMPTZ,
    last_failure_at        TIMESTAMPTZ,
    next_probe_at          TIMESTAMPTZ,
    probe_message          VARCHAR(1024),
    probe_evidence         VARCHAR(1024),
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_file_channel_health UNIQUE (tenant_id, channel_code),
    CONSTRAINT ck_file_channel_health_status CHECK (health_status IN ('HEALTHY', 'DEGRADED', 'UNHEALTHY')),
    CONSTRAINT ck_file_channel_health_failures CHECK (consecutive_failures >= 0)
);

CREATE INDEX IF NOT EXISTS idx_file_channel_health_next_probe
    ON batch.file_channel_health (health_status, next_probe_at);
