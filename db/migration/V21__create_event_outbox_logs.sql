-- =========================================================
-- V20 - Create outbox retry and delivery logs
-- 说明：
-- 1) 将重试记录与基础 Outbox 事件分开持久化。
-- 2) 记录投递状态，供下游审计和故障排查使用。
-- =========================================================

CREATE TABLE IF NOT EXISTS batch.event_outbox_retry (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    outbox_event_id BIGINT       NOT NULL REFERENCES batch.outbox_event(id),
    event_key       VARCHAR(256) NOT NULL,
    retry_attempt   INTEGER      NOT NULL DEFAULT 1,
    retry_status    VARCHAR(32)  NOT NULL,
    retry_reason    VARCHAR(1024),
    next_retry_at   TIMESTAMPTZ,
    trace_id        VARCHAR(128),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_event_outbox_retry_status CHECK (retry_status IN ('WAITING', 'RUNNING', 'SUCCESS', 'FAILED', 'EXHAUSTED', 'CANCELLED'))
);

CREATE TABLE IF NOT EXISTS batch.event_delivery_log (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        VARCHAR(64)  NOT NULL,
    outbox_event_id  BIGINT       NOT NULL REFERENCES batch.outbox_event(id),
    event_type       VARCHAR(64)  NOT NULL,
    event_key        VARCHAR(256) NOT NULL,
    target_topic     VARCHAR(256) NOT NULL,
    target_worker_id  VARCHAR(128),
    delivery_status  VARCHAR(32)  NOT NULL,
    delivery_attempt INTEGER      NOT NULL DEFAULT 1,
    delivery_summary JSONB,
    error_message    VARCHAR(1024),
    trace_id         VARCHAR(128),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_event_delivery_status CHECK (delivery_status IN ('PUBLISHED', 'FAILED', 'GIVE_UP'))
);

CREATE INDEX IF NOT EXISTS idx_event_outbox_retry_tenant_status
    ON batch.event_outbox_retry (tenant_id, retry_status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_event_delivery_log_tenant_status
    ON batch.event_delivery_log (tenant_id, delivery_status, created_at DESC);
