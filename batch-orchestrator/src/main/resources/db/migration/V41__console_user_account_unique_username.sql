-- 用户名全局唯一：登录时无需传 tenantId，后端按 username 查出所属租户。
-- 先删除原 (tenant_id, username) 唯一约束，再添加 (username) 唯一约束。
ALTER TABLE batch.console_user_account
    DROP CONSTRAINT IF EXISTS uk_console_user_account_tenant_username;

ALTER TABLE batch.console_user_account
    ADD CONSTRAINT uk_console_user_account_username UNIQUE (username);
