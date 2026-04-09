-- =========================================================
-- V10 - System parameter table for runtime configuration
-- =========================================================

CREATE TABLE IF NOT EXISTS batch.system_parameter (
    id            BIGSERIAL    PRIMARY KEY,
    tenant_id     VARCHAR(64)  NOT NULL,
    param_key     VARCHAR(128) NOT NULL,
    param_value   VARCHAR(2048) NOT NULL,
    description   VARCHAR(512),
    created_by    VARCHAR(64),
    updated_by    VARCHAR(64),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_system_parameter_tenant_key UNIQUE (tenant_id, param_key)
);
