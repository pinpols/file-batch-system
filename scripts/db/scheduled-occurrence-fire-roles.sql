-- 在平台库以权限管理员执行：指定现有 Trigger / Console 主库角色，授予或撤销事件表权限。
-- psql -X -v ON_ERROR_STOP=1 -v trigger_role=可信角色 -v console_role=可信角色 -v action=grant -f scripts/db/scheduled-occurrence-fire-roles.sql
\set ON_ERROR_STOP on
BEGIN;

\if :{?trigger_role}
\else
  DO $$ BEGIN RAISE EXCEPTION '必须指定 trigger_role'; END $$;
\endif
\if :{?console_role}
\else
  DO $$ BEGIN RAISE EXCEPTION '必须指定 console_role'; END $$;
\endif
\if :{?action}
\else
  DO $$ BEGIN RAISE EXCEPTION '必须指定 action=grant 或 revoke'; END $$;
\endif

SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'trigger_role' AND lower(rolname) <> 'public')
   AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'console_role' AND lower(rolname) <> 'public') AS roles_exist \gset
\if :roles_exist
\else
  DO $$ BEGIN RAISE EXCEPTION '角色不存在或不允许对 PUBLIC 授权'; END $$;
\endif
SELECT (:'action' = 'grant') AS is_grant, (:'action' = 'revoke') AS is_revoke \gset
\if :is_grant
  SELECT has_schema_privilege(:'trigger_role', 'batch', 'USAGE')
     AND has_schema_privilege(:'console_role', 'batch', 'USAGE') AS has_schema_usage \gset
  \if :has_schema_usage
  \else
    DO $$ BEGIN RAISE EXCEPTION '角色缺少 batch schema USAGE'; END $$;
  \endif
  SELECT format('GRANT SELECT, INSERT ON batch.scheduled_occurrence_fire TO %I', :'trigger_role') \gexec
  SELECT format('GRANT USAGE ON SEQUENCE batch.scheduled_occurrence_fire_id_seq TO %I', :'trigger_role') \gexec
  SELECT format('GRANT SELECT ON batch.scheduled_occurrence_fire TO %I', :'console_role') \gexec
\else
  \if :is_revoke
    SELECT format('REVOKE SELECT, INSERT ON batch.scheduled_occurrence_fire FROM %I', :'trigger_role') \gexec
    SELECT format('REVOKE USAGE ON SEQUENCE batch.scheduled_occurrence_fire_id_seq FROM %I', :'trigger_role') \gexec
    SELECT format('REVOKE SELECT ON batch.scheduled_occurrence_fire FROM %I', :'console_role') \gexec
  \else
    DO $$ BEGIN RAISE EXCEPTION 'action 只能是 grant 或 revoke'; END $$;
  \endif
\endif
COMMIT;
