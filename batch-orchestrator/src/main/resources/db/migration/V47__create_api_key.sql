-- API Key 自助管理：租户创建/吊销用于外部集成的 API Key。
CREATE TABLE IF NOT EXISTS batch.api_key (
    id              BIGSERIAL       PRIMARY KEY,
    tenant_id       VARCHAR(64)     NOT NULL,
    key_name        VARCHAR(128)    NOT NULL,
    key_prefix      VARCHAR(8)      NOT NULL,          -- 前 8 位明文，用于识别
    key_hash        VARCHAR(128)    NOT NULL,           -- SHA-256 哈希
    scopes          VARCHAR(512)    NOT NULL DEFAULT '*',
    enabled         BOOLEAN         NOT NULL DEFAULT TRUE,
    expires_at      TIMESTAMPTZ,
    last_used_at    TIMESTAMPTZ,
    created_by      VARCHAR(64),
    revoked_by      VARCHAR(64),
    revoked_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_api_key_tenant_name UNIQUE (tenant_id, key_name),
    CONSTRAINT uk_api_key_hash UNIQUE (key_hash)
);

CREATE INDEX IF NOT EXISTS idx_api_key_tenant ON batch.api_key (tenant_id, enabled);
CREATE INDEX IF NOT EXISTS idx_api_key_prefix ON batch.api_key (key_prefix);
