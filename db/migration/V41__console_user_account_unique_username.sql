-- 用户名全局唯一：登录时无需传 tenantId，后端按 username 查出所属租户。
-- 先删除原 (tenant_id, username) 唯一约束，再添加 (username) 唯一约束。
ALTER TABLE batch.console_user_account
    DROP CONSTRAINT IF EXISTS uk_console_user_account_tenant_username;

-- 先创建唯一索引（IF NOT EXISTS 防止残留索引导致重复创建），
-- 再基于已有索引添加约束（USING INDEX 不会再创建底层索引）。
CREATE UNIQUE INDEX IF NOT EXISTS uk_console_user_account_username
    ON batch.console_user_account (username);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uk_console_user_account_username'
          AND conrelid = 'batch.console_user_account'::regclass
    ) THEN
        ALTER TABLE batch.console_user_account
            ADD CONSTRAINT uk_console_user_account_username
            UNIQUE USING INDEX uk_console_user_account_username;
    END IF;
END $$;
