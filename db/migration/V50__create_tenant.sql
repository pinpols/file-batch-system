CREATE TABLE IF NOT EXISTS batch.tenant (
    id          BIGSERIAL    PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL,
    tenant_name VARCHAR(256) NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    description VARCHAR(512),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  VARCHAR(128),
    CONSTRAINT uk_tenant_tenant_id UNIQUE (tenant_id),
    CONSTRAINT ck_tenant_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

-- 同步 console_user_account 中已存在的 tenant_id，避免历史数据丢失
INSERT INTO batch.tenant (tenant_id, tenant_name, status, description, created_by)
SELECT DISTINCT
    tenant_id,
    tenant_id,
    'ACTIVE',
    'Migrated from console_user_account',
    'system'
FROM batch.console_user_account
ON CONFLICT (tenant_id) DO NOTHING;
