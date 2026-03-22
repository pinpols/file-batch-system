-- =========================================================
-- V10 - Create console AI audit log table
-- =========================================================

CREATE TABLE IF NOT EXISTS batch.console_ai_audit_log (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    request_id               VARCHAR(128) NOT NULL,
    trace_id                 VARCHAR(128),
    session_id               VARCHAR(128),
    operator_id              VARCHAR(64),
    prompt_category          VARCHAR(32)  NOT NULL,
    prompt_decision          VARCHAR(32)  NOT NULL,
    model_name               VARCHAR(128),
    prompt_hash              VARCHAR(128),
    prompt_preview           VARCHAR(1024),
    response_hash            VARCHAR(128),
    response_preview         VARCHAR(1024),
    refusal_reason           VARCHAR(512),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_console_ai_audit_request UNIQUE (tenant_id, request_id)
);
