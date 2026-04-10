-- =========================================================
-- V43 - Alert routing / notification policy configuration
-- Notes:
-- 1) Stores alert routing rules that map alert conditions to receivers.
-- 2) Aligns with Alertmanager route semantics (group_by, wait, interval, repeat).
-- =========================================================

CREATE TABLE IF NOT EXISTS batch.alert_routing_config (
    id                          BIGSERIAL PRIMARY KEY,
    tenant_id                   VARCHAR(64)  NOT NULL,
    route_code                  VARCHAR(128) NOT NULL,
    route_name                  VARCHAR(256) NOT NULL,
    team                        VARCHAR(128) NOT NULL,
    alert_group                 VARCHAR(128) NOT NULL,
    severity                    VARCHAR(16)  NOT NULL,
    receiver                    VARCHAR(256) NOT NULL,
    group_by                    VARCHAR(512),
    group_wait_seconds          INTEGER      NOT NULL DEFAULT 30,
    group_interval_seconds      INTEGER      NOT NULL DEFAULT 300,
    repeat_interval_seconds     INTEGER      NOT NULL DEFAULT 3600,
    enabled                     BOOLEAN      NOT NULL DEFAULT TRUE,
    description                 VARCHAR(1024),
    created_by                  VARCHAR(64),
    updated_by                  VARCHAR(64),
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_alert_routing_config UNIQUE (tenant_id, route_code),
    CONSTRAINT ck_alert_routing_severity CHECK (severity IN ('INFO', 'WARN', 'ERROR', 'CRITICAL'))
);

CREATE INDEX IF NOT EXISTS idx_alert_routing_config_tenant
    ON batch.alert_routing_config (tenant_id);

CREATE INDEX IF NOT EXISTS idx_alert_routing_config_team
    ON batch.alert_routing_config (tenant_id, team);
