-- =========================================================
-- V26 - Unified approval command workflow
-- Notes:
-- 1) Centralize approval requests, approvals, rejections, and execution state.
-- 2) Store source trace and idempotency keys for audit and replay safety.
-- =========================================================

CREATE TABLE IF NOT EXISTS batch.approval_command (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    approval_no         VARCHAR(128) NOT NULL,
    approval_type       VARCHAR(64)  NOT NULL,
    action_type         VARCHAR(64)  NOT NULL,
    target_type         VARCHAR(64)  NOT NULL,
    target_id           VARCHAR(128),
    payload_json        JSONB        NOT NULL,
    approval_status     VARCHAR(32)  NOT NULL,
    requester_id        VARCHAR(64),
    approver_id         VARCHAR(64),
    rejection_reason    VARCHAR(1024),
    approval_reason     VARCHAR(1024),
    source_trace_id     VARCHAR(128),
    source_idempotency_key VARCHAR(128),
    approved_at         TIMESTAMPTZ,
    executed_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_approval_command_tenant_no UNIQUE (tenant_id, approval_no),
    CONSTRAINT ck_approval_command_status CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXECUTED')),
    CONSTRAINT ck_approval_command_type CHECK (approval_type IN ('CATCH_UP', 'COMPENSATION', 'DLQ_REPLAY', 'DOWNLOAD')),
    CONSTRAINT ck_approval_command_action CHECK (action_type IN ('CATCH_UP', 'COMPENSATION', 'DLQ_REPLAY', 'DOWNLOAD', 'RETRY'))
);

CREATE INDEX IF NOT EXISTS idx_approval_command_status
    ON batch.approval_command (tenant_id, approval_status, created_at DESC);
