-- 2026-05 角色重设计:CONFIG_ADMIN 合并升级为 ADMIN。
--
-- 背景:CONFIG_ADMIN 历史上为"配置管理员"全局角色;新模型确立 4 档(ADMIN/TENANT_ADMIN/TENANT_USER/AUDITOR)后,
-- CONFIG_ADMIN 在平台层与 ADMIN 重叠,在租户层应由 TENANT_ADMIN 取代。已存在的 CONFIG_ADMIN 账号
-- 历史上被授予的就是「全局信任」,升级为 ADMIN 既保留权限又对齐新模型。
--
-- 落地:
--   1. authorities_csv 中含 ROLE_CONFIG_ADMIN 的账号 → 替换为 ROLE_ADMIN
--   2. 同时去重(若已含 ROLE_ADMIN,移除冗余的 ROLE_CONFIG_ADMIN)
--   3. updated_at 更新,审计可见
--
-- 操作审计:操作前数量记入 console_operation_audit(假设表存在;若无此表则跳过日志)。

UPDATE batch.console_user_account
SET authorities_csv = CASE
        -- 同时含 ADMIN 和 CONFIG_ADMIN → 去掉 CONFIG_ADMIN
        WHEN authorities_csv LIKE '%ROLE_ADMIN%' AND authorities_csv LIKE '%ROLE_CONFIG_ADMIN%' THEN
            REGEXP_REPLACE(authorities_csv, ',?\s*ROLE_CONFIG_ADMIN', '', 'g')
        -- 只含 CONFIG_ADMIN → 替换为 ADMIN
        ELSE
            REPLACE(authorities_csv, 'ROLE_CONFIG_ADMIN', 'ROLE_ADMIN')
    END,
    updated_at = CURRENT_TIMESTAMP
WHERE authorities_csv LIKE '%ROLE_CONFIG_ADMIN%';

-- 兜底清理:首尾可能残留的逗号
UPDATE batch.console_user_account
SET authorities_csv = TRIM(BOTH ',' FROM REGEXP_REPLACE(authorities_csv, ',+', ',', 'g')),
    updated_at = CURRENT_TIMESTAMP
WHERE authorities_csv ~ '^,|,$|,,';
