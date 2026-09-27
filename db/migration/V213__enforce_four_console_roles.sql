-- 控制台 RBAC 只保留四类正式角色。
--
-- 迁移阶段将官方历史 ROLE_USER 一次性转换为 ROLE_TENANT_USER；运行时不再
-- 接受旧 JWT 或旧角色写入。约束允许多角色 CSV，但每个值必须属于四角色集合。

UPDATE batch.console_user_account
SET authorities_csv = REPLACE(authorities_csv, 'ROLE_USER', 'ROLE_TENANT_USER'),
    updated_at = CURRENT_TIMESTAMP
WHERE authorities_csv ~ '(^|,\s*)ROLE_USER(\s*,|$)';

ALTER TABLE batch.console_user_account
    ADD CONSTRAINT ck_console_user_account_four_roles
    CHECK (
        authorities_csv ~
        '^(ROLE_ADMIN|ROLE_AUDITOR|ROLE_TENANT_ADMIN|ROLE_TENANT_USER)(\s*,\s*(ROLE_ADMIN|ROLE_AUDITOR|ROLE_TENANT_ADMIN|ROLE_TENANT_USER))*$'
    ) NOT VALID;

ALTER TABLE batch.console_user_account
    VALIDATE CONSTRAINT ck_console_user_account_four_roles;

COMMENT ON CONSTRAINT ck_console_user_account_four_roles ON batch.console_user_account IS
    '控制台账号仅允许 ADMIN、AUDITOR、TENANT_ADMIN、TENANT_USER 四类正式角色';
