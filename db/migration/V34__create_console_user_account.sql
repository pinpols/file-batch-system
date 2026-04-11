CREATE TABLE IF NOT EXISTS batch.console_user_account (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    username        VARCHAR(128) NOT NULL,
    display_name    VARCHAR(256),
    password_hash   VARCHAR(512) NOT NULL,
    authorities_csv VARCHAR(512) NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_console_user_account_tenant_username UNIQUE (tenant_id, username)
);

-- 初始账号通过 API (POST /api/console/users) 由 admin 创建，不在迁移中预置。
