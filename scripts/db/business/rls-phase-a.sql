-- =========================================================
-- Phase A · 业务库多租 RLS(Row-Level Security)
--
-- 见 docs/plans/multi-tenant-isolation-plan-2026-05-31.md §Phase A
-- 见 docs/runbook/multi-tenant-rls.md
--
-- 目标:在 biz.* 10 张表上加 PostgreSQL RLS,DB 层强制 `tenant_id` 过滤,
--      杜绝「应用 SQL bug → 跨租户数据泄露」。
--
-- 部署位置:batch_business 库(同 scripts/db/business/create_biz_tables.sql)。
--   psql -d batch_business -f scripts/db/business/rls-phase-a.sql
--
-- 策略模式:transition(过渡模式)— `current_setting('app.tenant_id', true)` 未设时允许
--   全部(向后兼容现有 worker),设了则强制 `tenant_id` 等于该值。
--   全部 worker 改造完成后,后续 PR 把 USING / WITH CHECK 的 IS NULL 分支去掉转为 strict
--   模式(显式 SET LOCAL 才能读写)。
--
-- BYPASSRLS:`batch_business_admin` role(本脚本创建)用于平台跨租户聚合(forensic
--   export / 跨租户报表)。普通应用 worker 不应使用此 role,审计日志会标记 role 名。
-- =========================================================

-- ---------------------------------------------------------
-- 1. DB roles 拆分:应用 R/W(RLS 强制) vs 平台聚合(BYPASSRLS)
-- ---------------------------------------------------------
-- 应用 worker role(RLS 生效)。已有部署 batch_user 继续可用,新部署优先用此 role。
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'batch_business_writer') THEN
    CREATE ROLE batch_business_writer LOGIN PASSWORD 'change_me_in_prod';
    COMMENT ON ROLE batch_business_writer IS 'biz.* R/W with RLS enforced. Phase A.';
  END IF;
END $$;

-- 平台聚合 role(BYPASSRLS,跨租户报表 / forensic export 用)。
-- 严禁给业务 worker 用此 role。审计日志按 role 名标记区分。
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'batch_business_admin') THEN
    CREATE ROLE batch_business_admin LOGIN PASSWORD 'change_me_in_prod' BYPASSRLS;
    COMMENT ON ROLE batch_business_admin IS 'BYPASSRLS for platform-wide aggregation only. Audited.';
  END IF;
END $$;

GRANT USAGE ON SCHEMA biz TO batch_business_writer, batch_business_admin;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA biz
  TO batch_business_writer, batch_business_admin;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA biz
  TO batch_business_writer, batch_business_admin;
ALTER DEFAULT PRIVILEGES IN SCHEMA biz
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES
  TO batch_business_writer, batch_business_admin;
ALTER DEFAULT PRIVILEGES IN SCHEMA biz
  GRANT USAGE, SELECT ON SEQUENCES
  TO batch_business_writer, batch_business_admin;

-- 旧 batch_user 显式 NOBYPASSRLS(若它是 superuser 也不影响 RLS 对 superuser 的豁免;
-- 生产应避免让应用用 superuser,以下 ALTER 仅在 batch_user 是普通 role 时生效)。
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'batch_user' AND NOT rolsuper) THEN
    ALTER ROLE batch_user NOBYPASSRLS;
  END IF;
END $$;

-- ---------------------------------------------------------
-- 2. 启用 RLS + 加 policy(9 张 biz 表 + 1 张 batch.process_staging)
--
-- 策略:transition 模式 — `app.tenant_id` 未设时允许全部(向后兼容);设了则强制等值。
--
-- 关键点:
--   - ENABLE 启动 RLS 检查
--   - FORCE 让表 owner 也受 RLS 约束(防止 owner 角色绕过)
--   - PERMISSIVE 是默认,多个 PERMISSIVE policy 是 OR(允许任一即通过)
--   - WITH CHECK 控制写入(INSERT/UPDATE 后的行必须满足条件)
--   - USING 控制读取(SELECT/UPDATE/DELETE 时按条件过滤)
-- ---------------------------------------------------------
DO $$
DECLARE
  t TEXT;
  tables TEXT[] := ARRAY[
    'biz.customer_account',
    'biz.customer_processed',
    'biz.process_account_summary',
    'biz.process_event_copy',
    'biz.process_order_event',
    'biz.risk_alert',
    'biz.risk_score',
    'biz.settlement_batch',
    'biz.settlement_detail',
    'biz.transaction'
  ];
BEGIN
  FOREACH t IN ARRAY tables LOOP
    EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', t);
    -- DROP 之前可能存在的同名 policy(幂等)
    EXECUTE format('DROP POLICY IF EXISTS tenant_isolation_transition ON %s', t);
    EXECUTE format($p$
      CREATE POLICY tenant_isolation_transition ON %s
        AS PERMISSIVE
        FOR ALL
        TO PUBLIC
        USING (
          current_setting('app.tenant_id', true) IS NULL
          OR current_setting('app.tenant_id', true) = ''
          OR tenant_id = current_setting('app.tenant_id', true)
        )
        WITH CHECK (
          current_setting('app.tenant_id', true) IS NULL
          OR current_setting('app.tenant_id', true) = ''
          OR tenant_id = current_setting('app.tenant_id', true)
        )
    $p$, t);
  END LOOP;
END $$;

-- batch.process_staging 也带 tenant_id,同步加 RLS
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables
             WHERE table_schema='batch' AND table_name='process_staging') THEN
    ALTER TABLE batch.process_staging ENABLE ROW LEVEL SECURITY;
    ALTER TABLE batch.process_staging FORCE ROW LEVEL SECURITY;
    DROP POLICY IF EXISTS tenant_isolation_transition ON batch.process_staging;
    CREATE POLICY tenant_isolation_transition ON batch.process_staging
      AS PERMISSIVE
      FOR ALL
      TO PUBLIC
      USING (
        current_setting('app.tenant_id', true) IS NULL
        OR current_setting('app.tenant_id', true) = ''
        OR tenant_id = current_setting('app.tenant_id', true)
      )
      WITH CHECK (
        current_setting('app.tenant_id', true) IS NULL
        OR current_setting('app.tenant_id', true) = ''
        OR tenant_id = current_setting('app.tenant_id', true)
      );
  END IF;
END $$;

-- ---------------------------------------------------------
-- 3. 验证(运行后人工检查)
-- ---------------------------------------------------------
-- SELECT schemaname, tablename, policyname, permissive, cmd
--   FROM pg_policies WHERE schemaname IN ('biz', 'batch') ORDER BY schemaname, tablename;
--
-- 期望:每张表 1 行,policyname='tenant_isolation_transition',permissive='PERMISSIVE',cmd='ALL'
