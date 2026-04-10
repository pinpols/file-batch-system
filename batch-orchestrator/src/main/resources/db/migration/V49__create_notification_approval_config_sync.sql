-- =========================================================
-- V49 - Notification subscription, config approval, config sync
-- =========================================================

-- ── 1. 通知订阅管理 ────────────────────────────────────────

CREATE TABLE IF NOT EXISTS batch.notification_channel (
    id              BIGSERIAL     PRIMARY KEY,
    tenant_id       VARCHAR(64)   NOT NULL,
    channel_code    VARCHAR(64)   NOT NULL,
    channel_name    VARCHAR(128)  NOT NULL,
    channel_type    VARCHAR(32)   NOT NULL,
    config_json     JSONB         NOT NULL DEFAULT '{}',
    enabled         BOOLEAN       NOT NULL DEFAULT TRUE,
    created_by      VARCHAR(64),
    updated_by      VARCHAR(64),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_notification_channel_tenant_code UNIQUE (tenant_id, channel_code),
    CONSTRAINT ck_notification_channel_type CHECK (channel_type IN ('EMAIL', 'DINGTALK', 'WECOM', 'WEBHOOK', 'SMS'))
);

CREATE TABLE IF NOT EXISTS batch.subscription_rule (
    id              BIGSERIAL     PRIMARY KEY,
    tenant_id       VARCHAR(64)   NOT NULL,
    rule_name       VARCHAR(128)  NOT NULL,
    channel_code    VARCHAR(64)   NOT NULL,
    event_types     VARCHAR(512)  NOT NULL,
    severity_filter VARCHAR(128),
    job_code_filter VARCHAR(512),
    enabled         BOOLEAN       NOT NULL DEFAULT TRUE,
    created_by      VARCHAR(64),
    updated_by      VARCHAR(64),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_subscription_rule_tenant_name UNIQUE (tenant_id, rule_name)
);

CREATE TABLE IF NOT EXISTS batch.notification_delivery_log (
    id              BIGSERIAL     PRIMARY KEY,
    tenant_id       VARCHAR(64)   NOT NULL,
    rule_id         BIGINT        NOT NULL,
    channel_code    VARCHAR(64)   NOT NULL,
    event_type      VARCHAR(64)   NOT NULL,
    alert_event_id  BIGINT,
    payload_json    JSONB         NOT NULL,
    delivery_status VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
    error_message   VARCHAR(1024),
    attempt         INTEGER       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_notification_delivery_status CHECK (delivery_status IN ('PENDING', 'SUCCESS', 'FAILED', 'EXHAUSTED'))
);

CREATE INDEX IF NOT EXISTS idx_notification_delivery_log_tenant
    ON batch.notification_delivery_log (tenant_id, created_at DESC);

-- ── 2. 配置审批流 ──────────────────────────────────────────

CREATE TABLE IF NOT EXISTS batch.config_approval (
    id              BIGSERIAL     PRIMARY KEY,
    tenant_id       VARCHAR(64)   NOT NULL,
    release_id      BIGINT        NOT NULL,
    approval_status VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
    requested_by    VARCHAR(64)   NOT NULL,
    requested_at    TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_by     VARCHAR(64),
    reviewed_at     TIMESTAMPTZ,
    review_comment  VARCHAR(1024),
    expired_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_config_approval_status CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED'))
);

CREATE INDEX IF NOT EXISTS idx_config_approval_tenant_status
    ON batch.config_approval (tenant_id, approval_status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_config_approval_release
    ON batch.config_approval (release_id);

-- expand config_release status CHECK to include PENDING_APPROVAL
ALTER TABLE batch.config_release DROP CONSTRAINT IF EXISTS ck_config_release_status;
ALTER TABLE batch.config_release ADD CONSTRAINT ck_config_release_status
    CHECK (config_status IN ('DRAFT', 'PENDING_APPROVAL', 'PUBLISHED', 'GRAY', 'ROLLED_BACK'));

-- expand config_change_log action CHECK to include SUBMIT_APPROVAL / APPROVE / REJECT
ALTER TABLE batch.config_change_log DROP CONSTRAINT IF EXISTS ck_config_change_log_action;
ALTER TABLE batch.config_change_log ADD CONSTRAINT ck_config_change_log_action
    CHECK (change_action IN ('CREATE', 'PUBLISH', 'GRAY', 'ROLLBACK', 'ROTATE', 'SUBMIT_APPROVAL', 'APPROVE', 'REJECT'));

-- ── 3. 环境间配置同步 ──────────────────────────────────────

CREATE TABLE IF NOT EXISTS batch.config_sync_log (
    id              BIGSERIAL     PRIMARY KEY,
    tenant_id       VARCHAR(64)   NOT NULL,
    sync_direction  VARCHAR(16)   NOT NULL,
    source_env      VARCHAR(64)   NOT NULL,
    target_env      VARCHAR(64)   NOT NULL,
    config_types    VARCHAR(256)  NOT NULL,
    total_items     INTEGER       NOT NULL DEFAULT 0,
    success_items   INTEGER       NOT NULL DEFAULT 0,
    failed_items    INTEGER       NOT NULL DEFAULT 0,
    skipped_items   INTEGER       NOT NULL DEFAULT 0,
    sync_status     VARCHAR(32)   NOT NULL DEFAULT 'RUNNING',
    detail_json     JSONB,
    operator_id     VARCHAR(64),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_config_sync_direction CHECK (sync_direction IN ('EXPORT', 'IMPORT')),
    CONSTRAINT ck_config_sync_status CHECK (sync_status IN ('RUNNING', 'SUCCESS', 'PARTIAL_FAILED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_config_sync_log_tenant
    ON batch.config_sync_log (tenant_id, created_at DESC);
