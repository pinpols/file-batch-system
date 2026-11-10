-- 用途：为可信 Import 或 Orchestrator 平台库角色授予或撤销入站归属的只读权限。
-- 用法：psql -X -v ON_ERROR_STOP=1 -v runtime_role=可信角色 -v action=grant -f scripts/db/import-ingress-ownership-role.sql
-- 此脚本不授予声明或修改权限；普通角色仍不能跨租户维护归属配置。

\set ON_ERROR_STOP on
\if :{?runtime_role}
\else
  DO $$ BEGIN RAISE EXCEPTION '必须指定可信 runtime_role'; END $$;
\endif
\if :{?action}
\else
  DO $$ BEGIN RAISE EXCEPTION '必须指定 grant 或 revoke'; END $$;
\endif

BEGIN;
SELECT EXISTS (
    SELECT 1 FROM pg_roles
     WHERE rolname = :'runtime_role' AND lower(rolname) <> 'public'
       AND NOT rolsuper AND NOT rolbypassrls
) AS valid_role,
:'action' = 'grant' AS is_grant, :'action' = 'revoke' AS is_revoke \gset
\if :valid_role
\else
  DO $$ BEGIN RAISE EXCEPTION '角色不存在、为 PUBLIC 或具备超级用户/RLS 绕过权限'; END $$;
\endif
\if :is_grant
\elif :is_revoke
\else
  DO $$ BEGIN RAISE EXCEPTION 'action 只能是 grant 或 revoke'; END $$;
\endif

SELECT 'ingress_owner_read_' || md5(:'runtime_role') AS policy_name \gset
SELECT EXISTS (
    SELECT 1 FROM pg_policy p
     WHERE p.polrelid IN ('batch.import_ingress_ownership'::regclass,
                          'batch.import_ingress_ownership_binding'::regclass)
       AND p.polname = :'policy_name'
       AND (p.polcmd <> 'r' OR NOT p.polpermissive
            OR p.polroles <> ARRAY[(SELECT oid FROM pg_roles WHERE rolname = :'runtime_role')]::oid[]
            OR COALESCE(pg_get_expr(p.polqual, p.polrelid), '') <> 'true')
) AS policy_conflict \gset
\if :policy_conflict
  DO $$ BEGIN RAISE EXCEPTION '同名归属读取策略与指定角色或预期条件不一致'; END $$;
\endif

\if :is_grant
  SELECT has_schema_privilege(:'runtime_role', 'batch', 'USAGE') AS schema_allowed \gset
  \if :schema_allowed
  \else
    DO $$ BEGIN RAISE EXCEPTION '运行角色缺少 batch schema USAGE'; END $$;
  \endif
  SELECT format('GRANT SELECT ON batch.import_ingress_ownership, batch.import_ingress_ownership_binding TO %I', :'runtime_role') \gexec
  SELECT format('CREATE POLICY %I ON batch.%I FOR SELECT TO %I USING (true)',
                :'policy_name', target.table_name, :'runtime_role')
    FROM (VALUES ('import_ingress_ownership'), ('import_ingress_ownership_binding')) target(table_name)
   WHERE NOT EXISTS (
       SELECT 1 FROM pg_policy p
        WHERE p.polrelid = format('batch.%I', target.table_name)::regclass AND p.polname = :'policy_name'
   ) \gexec
\else
  SELECT format('DROP POLICY %I ON batch.%I', :'policy_name', target.table_name)
    FROM (VALUES ('import_ingress_ownership'), ('import_ingress_ownership_binding')) target(table_name)
   WHERE EXISTS (
       SELECT 1 FROM pg_policy p
        WHERE p.polrelid = format('batch.%I', target.table_name)::regclass AND p.polname = :'policy_name'
   ) \gexec
  SELECT format('REVOKE SELECT ON batch.import_ingress_ownership, batch.import_ingress_ownership_binding FROM %I', :'runtime_role') \gexec
\endif
COMMIT;
