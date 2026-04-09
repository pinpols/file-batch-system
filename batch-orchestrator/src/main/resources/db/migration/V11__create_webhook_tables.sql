-- =========================================================
-- V11 - Webhook subscription and delivery log tables
-- =========================================================

CREATE TABLE IF NOT EXISTS batch.webhook_subscription (
    id            BIGSERIAL     PRIMARY KEY,
    tenant_id     VARCHAR(64)   NOT NULL,
    name          VARCHAR(128)  NOT NULL,
    callback_url  VARCHAR(1024) NOT NULL,
    event_types   VARCHAR(512)  NOT NULL,
    secret        VARCHAR(256),
    enabled       BOOLEAN       NOT NULL DEFAULT TRUE,
    created_by    VARCHAR(64),
    updated_by    VARCHAR(64),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_webhook_subscription_tenant_name UNIQUE (tenant_id, name)
);

CREATE TABLE IF NOT EXISTS batch.webhook_delivery_log (
    id              BIGSERIAL     PRIMARY KEY,
    tenant_id       VARCHAR(64)   NOT NULL,
    subscription_id BIGINT        NOT NULL REFERENCES batch.webhook_subscription(id),
    event_type      VARCHAR(64)   NOT NULL,
    payload_json    JSONB         NOT NULL,
    http_status     INTEGER,
    response_body   VARCHAR(2048),
    delivery_status VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
    attempt         INTEGER       NOT NULL DEFAULT 0,
    next_retry_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_webhook_delivery_status CHECK (delivery_status IN ('PENDING', 'SUCCESS', 'FAILED', 'EXHAUSTED'))
);

CREATE INDEX IF NOT EXISTS idx_webhook_delivery_log_sub ON batch.webhook_delivery_log (subscription_id);
CREATE INDEX IF NOT EXISTS idx_webhook_delivery_log_tenant ON batch.webhook_delivery_log (tenant_id, created_at DESC);
