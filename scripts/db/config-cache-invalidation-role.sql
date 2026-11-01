-- 用途：为明确指定的 Console 主库角色授予或撤销 V372 缓存失效队列权限。
-- 用法：psql -X -v ON_ERROR_STOP=1 -v console_role=可信角色 -v action=grant -f scripts/db/config-cache-invalidation-role.sql
-- 撤销时将 action 改为 revoke；脚本不创建账号，不调整 schema 或其他业务对象权限。

\set ON_ERROR_STOP on

BEGIN;

\if :{?console_role}
\else
  DO $$ BEGIN RAISE EXCEPTION '必须通过 console_role 指定已有的可信 Console 主库角色'; END $$;
\endif

\if :{?action}
\else
  DO $$ BEGIN RAISE EXCEPTION '必须通过 action 指定 grant 或 revoke'; END $$;
\endif

SELECT EXISTS (
    SELECT 1 FROM pg_roles WHERE rolname = :'console_role' AND lower(rolname) <> 'public'
) AS role_exists \gset
\if :role_exists
\else
  DO $$ BEGIN RAISE EXCEPTION '指定角色不存在，或不允许对 PUBLIC 授权'; END $$;
\endif

SELECT (:'action' = 'grant') AS is_grant, (:'action' = 'revoke') AS is_revoke \gset
\if :is_grant
\else
  \if :is_revoke
  \else
    DO $$ BEGIN RAISE EXCEPTION 'action 只能是 grant 或 revoke'; END $$;
  \endif
\endif

-- 使用固定前缀与角色摘要，避免超长角色名被 PostgreSQL 截断而改变目标。
SELECT 'config_cache_console_' || md5(:'console_role') AS policy_name \gset

-- 发现同名但定义不同的策略时停止，不能覆盖或删除其他策略。
SELECT EXISTS (
    SELECT 1
      FROM pg_policy p
     WHERE p.polrelid = 'batch.config_cache_invalidation_outbox'::regclass
       AND p.polname = :'policy_name'
       AND (
           p.polcmd <> '*'
           OR NOT p.polpermissive
           OR p.polroles <> ARRAY[(SELECT oid FROM pg_roles WHERE rolname = :'console_role')]::oid[]
           OR COALESCE(pg_get_expr(p.polqual, p.polrelid), '') <> 'true'
           OR COALESCE(pg_get_expr(p.polwithcheck, p.polrelid), '') <> 'true'
       )
) AS policy_conflict \gset
\if :policy_conflict
  DO $$ BEGIN RAISE EXCEPTION '同名 RLS 策略与指定角色或预期条件不一致'; END $$;
\endif

\if :is_grant
  -- schema 权限归既有平台授权管理，不由本脚本增加或撤销。
  SELECT has_schema_privilege(:'console_role', 'batch', 'USAGE') AS has_schema_usage \gset
  \if :has_schema_usage
  \else
    DO $$ BEGIN RAISE EXCEPTION '指定角色缺少 batch schema USAGE，请先按平台权限流程配置'; END $$;
  \endif

  SELECT format(
      'GRANT SELECT, INSERT, UPDATE, DELETE ON batch.config_cache_invalidation_outbox TO %I',
      :'console_role'
  ) \gexec
  SELECT format(
      'GRANT USAGE ON SEQUENCE batch.config_cache_invalidation_outbox_id_seq TO %I',
      :'console_role'
  ) \gexec
  SELECT format(
      'CREATE POLICY %I ON batch.config_cache_invalidation_outbox TO %I USING (true) WITH CHECK (true)',
      :'policy_name', :'console_role'
  )
   WHERE NOT EXISTS (
       SELECT 1 FROM pg_policy
        WHERE polrelid = 'batch.config_cache_invalidation_outbox'::regclass
          AND polname = :'policy_name'
   ) \gexec
\else
  SELECT format(
      'DROP POLICY %I ON batch.config_cache_invalidation_outbox', :'policy_name'
  )
   WHERE EXISTS (
       SELECT 1 FROM pg_policy
        WHERE polrelid = 'batch.config_cache_invalidation_outbox'::regclass
          AND polname = :'policy_name'
   ) \gexec
  SELECT format(
      'REVOKE SELECT, INSERT, UPDATE, DELETE ON batch.config_cache_invalidation_outbox FROM %I',
      :'console_role'
  ) \gexec
  SELECT format(
      'REVOKE USAGE ON SEQUENCE batch.config_cache_invalidation_outbox_id_seq FROM %I',
      :'console_role'
  ) \gexec
\endif

COMMIT;
