-- admin 账号只保留 ROLE_ADMIN，职责单一。
-- ROLE_AUDITOR 由 auditor 账号承担，ROLE_CONFIG_ADMIN 由 config-admin 账号承担。
UPDATE batch.console_user_account
SET authorities_csv = 'ROLE_ADMIN',
    updated_at      = CURRENT_TIMESTAMP
WHERE tenant_id = 'system'
  AND username = 'admin'
  AND authorities_csv != 'ROLE_ADMIN';
