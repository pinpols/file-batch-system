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
--   - DROP transition policy(允许 IS NULL/空 兜底)
--   - CREATE strict policy(必须 SET LOCAL app.tenant_id 才能读写)
--   - 漏 SET 的 SQL → SELECT 返 0 行 / INSERT 抛 row-level security violation
--
-- 回滚:`psql -d batch_business -f scripts/db/business/rls-phase-a-rollback-to-transition.sql`
-- =========================================================

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
-- 期望:9 张 biz 表 + 1 张 batch.process_staging
