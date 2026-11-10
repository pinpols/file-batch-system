-- 用途：为指定 Import Worker 主库角色授予或撤销入口扫描协调状态的跨租户访问权限。
-- 用法：psql -X -v ON_ERROR_STOP=1 -v import_role=可信角色 -v action=grant -f scripts/db/import-ingress-scan-role.sql
-- 撤销时将 action 改为 revoke；脚本不创建账号，也不调整其他业务对象权限。

\set ON_ERROR_STOP on

BEGIN;

\if :{?import_role}
\else
  DO $$ BEGIN RAISE EXCEPTION '必须通过 import_role 指定已有的可信 Import 主库角色'; END $$;
\endif

\if :{?action}
\else
  DO $$ BEGIN RAISE EXCEPTION '必须通过 action 指定 grant 或 revoke'; END $$;
\endif

SELECT EXISTS (
    SELECT 1 FROM pg_roles
     WHERE rolname = :'import_role' AND lower(rolname) <> 'public'
       AND NOT rolsuper AND NOT rolbypassrls
) AS role_exists \gset
\if :role_exists
\else
  DO $$ BEGIN RAISE EXCEPTION '指定角色不存在、为 PUBLIC 或具备 SUPERUSER/BYPASSRLS'; END $$;
\endif

SELECT (:'action' = 'grant') AS is_grant, (:'action' = 'revoke') AS is_revoke \gset
\if :is_grant
\elif :is_revoke
\else
  DO $$ BEGIN RAISE EXCEPTION 'action 只能是 grant 或 revoke'; END $$;
\endif

-- 策略名称按角色生成；同名但定义不符时拒绝覆盖或删除。
SELECT 'ingress_scan_import_' || md5(:'import_role') AS policy_name \gset
SELECT EXISTS (
    SELECT 1
      FROM pg_policy p
     WHERE p.polrelid IN ('batch.import_ingress_scan_storage_lease'::regclass,
                          'batch.import_ingress_scan_state'::regclass,
                          'batch.import_ingress_scan_object'::regclass)
       AND p.polname = :'policy_name'
       AND (
           p.polcmd <> '*'
           OR NOT p.polpermissive
           OR p.polroles <> ARRAY[(SELECT oid FROM pg_roles WHERE rolname = :'import_role')]::oid[]
           OR COALESCE(pg_get_expr(p.polqual, p.polrelid), '') <> 'true'
           OR COALESCE(pg_get_expr(p.polwithcheck, p.polrelid), '') <> 'true'
       )
) AS policy_conflict \gset
\if :policy_conflict
  DO $$ BEGIN RAISE EXCEPTION '同名扫描协调 RLS 策略与指定角色或预期条件不一致'; END $$;
\endif

\if :is_grant
  SELECT has_schema_privilege(:'import_role', 'batch', 'USAGE') AS has_schema_usage \gset
  \if :has_schema_usage
  \else
    DO $$ BEGIN RAISE EXCEPTION '指定角色缺少 batch schema USAGE，请先执行平台 Worker 授权'; END $$;
  \endif

  SELECT format('GRANT SELECT, INSERT, UPDATE ON batch.import_ingress_scan_storage_lease, batch.import_ingress_scan_state TO %I', :'import_role') \gexec
  SELECT format('GRANT SELECT, INSERT, UPDATE, DELETE ON batch.import_ingress_scan_object TO %I', :'import_role') \gexec
  SELECT format('GRANT USAGE ON SEQUENCE batch.import_ingress_scan_object_id_seq TO %I', :'import_role') \gexec

  SELECT format('CREATE POLICY %I ON batch.%I TO %I USING (true) WITH CHECK (true)',
                :'policy_name', target.table_name, :'import_role')
    FROM (VALUES ('import_ingress_scan_storage_lease'), ('import_ingress_scan_state'), ('import_ingress_scan_object')) target(table_name)
   WHERE NOT EXISTS (
       SELECT 1 FROM pg_policy p
        WHERE p.polrelid = format('batch.%I', target.table_name)::regclass
          AND p.polname = :'policy_name'
   ) \gexec
\else
  SELECT format('DROP POLICY %I ON batch.%I', :'policy_name', target.table_name)
    FROM (VALUES ('import_ingress_scan_storage_lease'), ('import_ingress_scan_state'), ('import_ingress_scan_object')) target(table_name)
   WHERE EXISTS (
       SELECT 1 FROM pg_policy p
        WHERE p.polrelid = format('batch.%I', target.table_name)::regclass
          AND p.polname = :'policy_name'
   ) \gexec
  SELECT format('REVOKE ALL ON batch.import_ingress_scan_storage_lease, batch.import_ingress_scan_state, batch.import_ingress_scan_object FROM %I', :'import_role') \gexec
  SELECT format('REVOKE USAGE ON SEQUENCE batch.import_ingress_scan_object_id_seq FROM %I', :'import_role') \gexec
\endif

COMMIT;
