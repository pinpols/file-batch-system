-- =========================================================
-- V18 - Operational alert event table
-- Notes:
-- 1) Persist deduplicated alert records for console query and operations handling.
-- 2) Track occurrence counts, severity, and status transitions on one table.
-- =========================================================

CREATE TABLE IF NOT EXISTS batch.alert_event (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    service_name        VARCHAR(64)  NOT NULL,
    alert_type          VARCHAR(64)  NOT NULL,
    severity            VARCHAR(16)  NOT NULL,
    title               VARCHAR(512) NOT NULL,
    detail_json         JSONB,
    dedup_fingerprint   VARCHAR(128) NOT NULL,
    occurrence_count    INTEGER      NOT NULL DEFAULT 1,
    first_seen_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    trace_id            VARCHAR(128),
    status              VARCHAR(32)  NOT NULL DEFAULT 'OPEN',
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_alert_event_dedup UNIQUE (tenant_id, dedup_fingerprint),
    CONSTRAINT ck_alert_event_severity CHECK (severity IN ('INFO', 'WARN', 'ERROR', 'CRITICAL')),
    CONSTRAINT ck_alert_event_status CHECK (status IN ('OPEN', 'ACKED', 'SUPPRESSED', 'CLOSED')),
    CONSTRAINT ck_alert_event_occurrence CHECK (occurrence_count > 0)
);

CREATE INDEX IF NOT EXISTS idx_alert_event_tenant_last_seen
    ON batch.alert_event (tenant_id, last_seen_at DESC);

CREATE INDEX IF NOT EXISTS idx_alert_event_type_severity
    ON batch.alert_event (tenant_id, alert_type, severity);
