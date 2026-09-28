-- 防御性处理递归式 AuditParamRedactor 引入前写入的历史记录。
-- 已知包含密码的请求 DTO 会嵌套在控制器参数名 "request" 下。
UPDATE batch.console_operation_audit
SET params = jsonb_set(params, '{request,password}', '"[REDACTED]"'::jsonb, false)
WHERE params #> '{request,password}' IS NOT NULL;

UPDATE batch.console_operation_audit
SET params = jsonb_set(params, '{request,newPassword}', '"[REDACTED]"'::jsonb, false)
WHERE params #> '{request,newPassword}' IS NOT NULL;

UPDATE batch.console_operation_audit
SET params = jsonb_set(params, '{request,currentPassword}', '"[REDACTED]"'::jsonb, false)
WHERE params #> '{request,currentPassword}' IS NOT NULL;

UPDATE batch.console_operation_audit
SET params = jsonb_set(params, '{password}', '"[REDACTED]"'::jsonb, false)
WHERE params #> '{password}' IS NOT NULL;
