-- 删除 V34 预置的 default-tenant 内置演示账号。
-- demo 模式已移除，内置账号应通过 API 由 admin 按需创建。
-- 幂等：账号不存在时 DELETE 不报错。
DELETE FROM batch.console_user_account
WHERE tenant_id = 'default-tenant'
  AND username IN ('admin', 'auditor', 'config-admin');
