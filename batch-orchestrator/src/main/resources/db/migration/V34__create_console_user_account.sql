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

-- 默认明文均为 admin123；Argon2id 与 batch-console-api ConsolePasswordHasher 一致（见 V35 对存量 PBKDF2 的升级）。
INSERT INTO batch.console_user_account (tenant_id, username, display_name, password_hash, authorities_csv, enabled)
VALUES
    ('default-tenant', 'admin', 'Console Admin', '$argon2id$v=19$m=16384,t=2,p=1$k18enAVVcHofGDMPXPxj5A$5TityFxKIX2z6bkuDXRHqmwuPcfr+G9MEA36Kr6fC4s', 'ROLE_ADMIN,ROLE_AUDITOR,ROLE_CONFIG_ADMIN', TRUE),
    ('default-tenant', 'auditor', 'Console Auditor', '$argon2id$v=19$m=16384,t=2,p=1$k18enAVVcHofGDMPXPxj5A$5TityFxKIX2z6bkuDXRHqmwuPcfr+G9MEA36Kr6fC4s', 'ROLE_AUDITOR', TRUE),
    ('default-tenant', 'config-admin', 'Console Config Admin', '$argon2id$v=19$m=16384,t=2,p=1$k18enAVVcHofGDMPXPxj5A$5TityFxKIX2z6bkuDXRHqmwuPcfr+G9MEA36Kr6fC4s', 'ROLE_CONFIG_ADMIN', TRUE)
ON CONFLICT (tenant_id, username) DO UPDATE
SET display_name = EXCLUDED.display_name,
    password_hash = EXCLUDED.password_hash,
    authorities_csv = EXCLUDED.authorities_csv,
    enabled = EXCLUDED.enabled,
    updated_at = CURRENT_TIMESTAMP;
