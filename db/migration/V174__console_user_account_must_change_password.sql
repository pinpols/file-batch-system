-- 控制台账号增加「首次登录强制改密」标志。
-- 向后兼容:默认 false(现有账号行为不变);仅内置出厂账号 / reset 路径会置 true。
-- 内置账号(V52 种的 admin/auditor/config-admin)出厂密码均为明文 admin123,
-- 这里把它们标记为 must_change_password=true,首次登录后强制改密。
-- console_user_account 无 archive.*_archive 镜像(非归档表),故无需同步归档迁移。

ALTER TABLE batch.console_user_account
    ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

-- 内置出厂账号:首次登录强制改密。
UPDATE batch.console_user_account
   SET must_change_password = TRUE
 WHERE tenant_id = 'system'
   AND username IN ('admin', 'auditor', 'config-admin');
