-- Defense in depth for rows written before recursive AuditParamRedactor was introduced.
-- Known password-bearing request DTOs are nested under the controller parameter name "request".
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
