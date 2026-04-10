-- =========================================================
-- V21 - Create config release, secret version and change log tables
-- Notes:
-- 1) Keep configuration publishing and secret rotation history queryable.
-- 2) Record versioning, gray release, and rollback lifecycle on the same schema.
-- =========================================================

CREATE TABLE IF NOT EXISTS batch.config_release (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    config_type         VARCHAR(32)  NOT NULL,
    config_key          VARCHAR(128) NOT NULL,
    config_name         VARCHAR(256) NOT NULL,
    config_status       VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    version_no          INTEGER      NOT NULL DEFAULT 1,
    gray_scope          JSONB,
    config_payload      JSONB,
    effective_from_at   TIMESTAMPTZ,
    effective_to_at     TIMESTAMPTZ,
    published_at        TIMESTAMPTZ,
    rolled_back_at      TIMESTAMPTZ,
    created_by          VARCHAR(64),
    updated_by          VARCHAR(64),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_config_release_tenant_type_key_version UNIQUE (tenant_id, config_type, config_key, version_no),
    CONSTRAINT ck_config_release_status CHECK (config_status IN ('DRAFT', 'PUBLISHED', 'GRAY', 'ROLLED_BACK')),
    CONSTRAINT ck_config_release_version CHECK (version_no > 0)
);

CREATE TABLE IF NOT EXISTS batch.secret_version (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    secret_ref               VARCHAR(128) NOT NULL,
    secret_name              VARCHAR(256) NOT NULL,
    version_no               INTEGER      NOT NULL DEFAULT 1,
    secret_status            VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    current_version          BOOLEAN      NOT NULL DEFAULT FALSE,
    rotation_window_start_at TIMESTAMPTZ,
    rotation_window_end_at   TIMESTAMPTZ,
    effective_from_at        TIMESTAMPTZ,
    effective_to_at          TIMESTAMPTZ,
    secret_payload           JSONB,
    rotation_reason          VARCHAR(512),
    created_by               VARCHAR(64),
    updated_by               VARCHAR(64),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_secret_version_tenant_ref_version UNIQUE (tenant_id, secret_ref, version_no),
    CONSTRAINT ck_secret_version_status CHECK (secret_status IN ('DRAFT', 'PUBLISHED', 'GRAY', 'ROLLED_BACK')),
    CONSTRAINT ck_secret_version_version CHECK (version_no > 0)
);

CREATE TABLE IF NOT EXISTS batch.config_change_log (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        VARCHAR(64)  NOT NULL,
    config_type      VARCHAR(32)  NOT NULL,
    config_key       VARCHAR(128) NOT NULL,
    version_no       INTEGER      NOT NULL,
    change_action    VARCHAR(32)  NOT NULL,
    change_result    VARCHAR(32)  NOT NULL,
    operator_type    VARCHAR(32)  NOT NULL DEFAULT 'API',
    operator_id      VARCHAR(64),
    trace_id         VARCHAR(128),
    change_summary   JSONB,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_config_change_log_action CHECK (change_action IN ('CREATE', 'PUBLISH', 'GRAY', 'ROLLBACK', 'ROTATE')),
    CONSTRAINT ck_config_change_log_result CHECK (change_result IN ('SUCCESS', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_config_release_tenant_status
    ON batch.config_release (tenant_id, config_status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_secret_version_tenant_status
    ON batch.secret_version (tenant_id, secret_status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_config_change_log_tenant_action
    ON batch.config_change_log (tenant_id, change_action, created_at DESC);
