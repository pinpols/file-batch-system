-- 创建系统管理租户，并种入三个内置运营账号。
-- 内置账号密码明文均为 admin123（Argon2id，与 ConsolePasswordHasher 一致）；
-- 首次部署后请通过 POST /api/console/users/{id}/reset-password 立即修改密码。

INSERT INTO batch.tenant (tenant_id, tenant_name, status, description, created_by)
VALUES ('system', 'System', 'ACTIVE', 'Built-in system management tenant', 'system')
ON CONFLICT (tenant_id) DO NOTHING;

INSERT INTO batch.console_user_account
    (tenant_id, username, display_name, password_hash, authorities_csv, enabled)
VALUES
    ('system', 'admin',        'Console Admin',
     '$argon2id$v=19$m=16384,t=2,p=1$k18enAVVcHofGDMPXPxj5A$5TityFxKIX2z6bkuDXRHqmwuPcfr+G9MEA36Kr6fC4s',
     'ROLE_ADMIN', TRUE),
    ('system', 'auditor',      'Console Auditor',
     '$argon2id$v=19$m=16384,t=2,p=1$k18enAVVcHofGDMPXPxj5A$5TityFxKIX2z6bkuDXRHqmwuPcfr+G9MEA36Kr6fC4s',
     'ROLE_AUDITOR', TRUE),
    ('system', 'config-admin', 'Console Config Admin',
     '$argon2id$v=19$m=16384,t=2,p=1$k18enAVVcHofGDMPXPxj5A$5TityFxKIX2z6bkuDXRHqmwuPcfr+G9MEA36Kr6fC4s',
     'ROLE_CONFIG_ADMIN', TRUE)
ON CONFLICT (username) DO NOTHING;
