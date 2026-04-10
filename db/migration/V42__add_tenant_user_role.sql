-- 新增普通租户用户角色（ROLE_TENANT_USER）：查看作业/文件状态、触发作业、下载文件，不能修改配置和做运维操作。
INSERT INTO batch.console_user_account (tenant_id, username, display_name, password_hash, authorities_csv, enabled)
VALUES
    ('default-tenant', 'tenant-user', 'Tenant User', '$argon2id$v=19$m=16384,t=2,p=1$k18enAVVcHofGDMPXPxj5A$5TityFxKIX2z6bkuDXRHqmwuPcfr+G9MEA36Kr6fC4s', 'ROLE_TENANT_USER', TRUE)
ON CONFLICT (username) DO UPDATE
SET display_name = EXCLUDED.display_name,
    password_hash = EXCLUDED.password_hash,
    authorities_csv = EXCLUDED.authorities_csv,
    enabled = EXCLUDED.enabled,
    updated_at = CURRENT_TIMESTAMP;
