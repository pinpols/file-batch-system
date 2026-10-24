-- =========================================================
-- Phase A · 回滚 strict → transition(应急脚本)
--
-- 触发场景:
--   1. 翻 strict 后某 worker 路径漏接线,大量 "row-level security violation" 告警
--   2. 翻 strict 后部分租户业务数据访问异常(本应有数据,SELECT 返 0 行)
--   3. 部分聚合 query 没用 batch_business_admin role 走 RLS 拦了
--
-- 部署(on-call 5 分钟内可恢复):
--   psql -d batch_business -f scripts/db/business/rls-phase-a-rollback-to-transition.sql
--
-- 效果:
--   - 删除 strict policy
--   - 重建 transition policy(允许 IS NULL/空 回退)
--   - 漏 SET 的应用代码恢复正常工作
--
-- 后续:复盘根因(为何漏接线),修复后再次走翻 strict 流程(见 rls-phase-a-strict.sql)




-- =========================================================

\set ON_ERROR_STOP on
BEGIN;

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
    ORDER BY c.relname
  LOOP
    -- 存在性守护:缺表只跳过并告警,不让整个 DO 块回滚。
    IF to_regclass(t) IS NULL THEN
      RAISE NOTICE 'rls-phase-a-rollback: skip missing table % (policy not restored)', t;
      CONTINUE;
    END IF;
    EXECUTE format('DROP POLICY IF EXISTS tenant_isolation_strict ON %s', t);
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

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables
             WHERE table_schema='batch' AND table_name='process_staging') THEN
    DROP POLICY IF EXISTS tenant_isolation_strict ON batch.process_staging;
    DROP POLICY IF EXISTS tenant_isolation_transition ON batch.process_staging;
    CREATE POLICY tenant_isolation_transition ON batch.process_staging
      AS PERMISSIVE FOR ALL TO PUBLIC
      USING (
        (current_setting('app.tenant_id', true) IS NULL
          OR current_setting('app.tenant_id', true) = ''
          OR tenant_id = current_setting('app.tenant_id', true))
        AND staging_generation_id::text = current_setting('app.process_staging_generation_id', true)
      )
      WITH CHECK (
        (current_setting('app.tenant_id', true) IS NULL
          OR current_setting('app.tenant_id', true) = ''
          OR tenant_id = current_setting('app.tenant_id', true))
        AND staging_generation_id::text = current_setting('app.process_staging_generation_id', true)
      );
  END IF;
END $$;

-- 分区叶子允许被直接命名访问,父表策略不会代替叶子自己的策略;保持 rollback 脚本的 policy 模式一致。
DO $$
DECLARE
  child_table TEXT;
BEGIN
  FOR child_table IN
    SELECT format('%I.%I', child_ns.nspname, child.relname)
    FROM pg_inherits i
    JOIN pg_class parent ON parent.oid = i.inhparent
    JOIN pg_namespace parent_ns ON parent_ns.oid = parent.relnamespace
    JOIN pg_class child ON child.oid = i.inhrelid
    JOIN pg_namespace child_ns ON child_ns.oid = child.relnamespace
    JOIN information_schema.columns col
      ON col.table_schema = child_ns.nspname
     AND col.table_name = child.relname
     AND col.column_name = 'tenant_id'
    WHERE parent_ns.nspname = 'batch'
      AND parent.relname = 'process_staging'
      AND child.relispartition
    ORDER BY child.relname
  LOOP
    EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', child_table);
    EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', child_table);
    EXECUTE format('DROP POLICY IF EXISTS tenant_isolation_strict ON %s', child_table);
    EXECUTE format('DROP POLICY IF EXISTS tenant_isolation_transition ON %s', child_table);
    EXECUTE format($policy$
      CREATE POLICY tenant_isolation_transition ON %s
        AS PERMISSIVE FOR ALL TO PUBLIC
        USING ((current_setting('app.tenant_id', true) IS NULL OR current_setting('app.tenant_id', true) = '' OR tenant_id = current_setting('app.tenant_id', true))
          AND staging_generation_id::text = current_setting('app.process_staging_generation_id', true))
        WITH CHECK ((current_setting('app.tenant_id', true) IS NULL OR current_setting('app.tenant_id', true) = '' OR tenant_id = current_setting('app.tenant_id', true))
          AND staging_generation_id::text = current_setting('app.process_staging_generation_id', true))
    $policy$, child_table);
  END LOOP;
END $$;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables
             WHERE table_schema='batch' AND table_name='process_staging_generation') THEN
    DROP POLICY IF EXISTS tenant_isolation_strict ON batch.process_staging_generation;
    DROP POLICY IF EXISTS tenant_isolation_transition ON batch.process_staging_generation;
    CREATE POLICY tenant_isolation_transition ON batch.process_staging_generation
      AS PERMISSIVE FOR ALL TO PUBLIC
      USING (current_setting('app.tenant_id', true) IS NULL
        OR current_setting('app.tenant_id', true) = ''
        OR tenant_id = current_setting('app.tenant_id', true))
      WITH CHECK (current_setting('app.tenant_id', true) IS NULL
        OR current_setting('app.tenant_id', true) = ''
        OR tenant_id = current_setting('app.tenant_id', true));
  END IF;
END $$;

-- 全部父/叶策略一次提交，禁止后续步骤失败时留下半切换状态。
COMMIT;
