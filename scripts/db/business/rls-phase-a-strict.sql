-- =========================================================
-- Phase A · 翻 strict 模式(从 transition policy → strict policy)
--
-- 见 docs/runbook/multi-tenant-rls-strict-rollout.md
--
-- 前置条件(详见 runbook §3.3 checklist):
--   A. 所有 biz.* 写入/读取路径已接线(PR #155/#158/#160 已完成)
--   B. sim 跑全链路一遍,日志无 "RLS SET LOCAL failed"
--   C. prod-shadow 跑 ≥ 1 周,actuator/health 持续绿
--   D. RlsStrictModePreflightIntegrationTest 通过(本 PR 已加)
--   E. release notes + 回滚预案(本 PR 同时提交 rls-phase-a-rollback-to-transition.sql)
--
-- 部署:
--   psql -d batch_business -f scripts/db/business/rls-phase-a-strict.sql
--
-- 效果:
--   - DROP transition policy(允许 IS NULL/空 回退)
--   - CREATE strict policy(必须 SET LOCAL app.tenant_id 才能读写)
--   - 漏 SET 的 SQL → SELECT 返 0 行 / INSERT 抛 row-level security violation
--
-- 回滚:`psql -d batch_business -f scripts/db/business/rls-phase-a-rollback-to-transition.sql`
-- =========================================================

DO $$
DECLARE
  t TEXT;
BEGIN
  FOR t IN
    SELECT format('%I.%I', n.nspname, c.relname)
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    JOIN information_schema.columns col
      ON col.table_schema = n.nspname
     AND col.table_name = c.relname
     AND col.column_name = 'tenant_id'
    WHERE n.nspname = 'biz'
      AND c.relkind IN ('r', 'p')
      AND c.relispartition = false
    ORDER BY c.relname
  LOOP
    -- 存在性守护:缺表只跳过并告警,不让整个 DO 块回滚。
    IF to_regclass(t) IS NULL THEN
      RAISE NOTICE 'rls-phase-a-strict: skip missing table % (RLS not applied)', t;
      CONTINUE;
    END IF;
    EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('DROP POLICY IF EXISTS tenant_isolation_transition ON %s', t);
    EXECUTE format('DROP POLICY IF EXISTS tenant_isolation_strict ON %s', t);
    EXECUTE format($p$
      CREATE POLICY tenant_isolation_strict ON %s
        AS PERMISSIVE
        FOR ALL
        TO PUBLIC
        USING (tenant_id = current_setting('app.tenant_id', true))
        WITH CHECK (tenant_id = current_setting('app.tenant_id', true))
    $p$, t);
  END LOOP;
END $$;

-- batch.process_staging 同步
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables
             WHERE table_schema='batch' AND table_name='process_staging') THEN
    ALTER TABLE batch.process_staging ENABLE ROW LEVEL SECURITY;
    ALTER TABLE batch.process_staging FORCE ROW LEVEL SECURITY;
    DROP POLICY IF EXISTS tenant_isolation_transition ON batch.process_staging;
    DROP POLICY IF EXISTS tenant_isolation_strict ON batch.process_staging;
    CREATE POLICY tenant_isolation_strict ON batch.process_staging
      AS PERMISSIVE FOR ALL TO PUBLIC
      USING (tenant_id = current_setting('app.tenant_id', true))
      WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
  END IF;
END $$;

-- 验证
-- SELECT schemaname, tablename, policyname FROM pg_policies
--   WHERE schemaname IN ('biz','batch') AND policyname='tenant_isolation_strict';
-- 期望:所有包含 tenant_id 的 biz 表 + batch.process_staging
