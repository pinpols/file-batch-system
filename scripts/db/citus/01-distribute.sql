-- =========================================================
-- scripts/db/citus/01-distribute.sql
-- Citus 启用脚本:把 Flyway V1..V178 建好的平台库转为 distributed/reference。
--
-- ⚠️ 只在 Citus coordinator 上执行(需 citus 扩展 + worker 已注册);
--    **绝不进 Flyway**(普通 PG 无 citus 扩展会失败)。
-- ⚠️ 执行前置:coordinator `ALTER SYSTEM SET citus.propagate_set_commands='local'`
--    (RLS 透传硬前提,POC 实测:docs/analysis/citus-poc-gates-2026-06-11.md)。
--
-- 分类规则(docs/plans/2026-06-10-partition-citus-paving-plan.md Phase 2 矩阵):
--   distributed = PK 含 tenant_id 的表(W1-W6 已复合化),按 tenant_id 分片,全部 colocate
--   reference   = 有 tenant_id 列但 PK 未复合化的配置/字典表(全分片复制)
--   local       = 显式例外(高频写基础设施/无租户维度系统表)
-- =========================================================

\set ON_ERROR_STOP on

-- 触发器放行:workflow_run 带 trg_workflow_run_tenant_check(跨租户一致性,查
-- related job_instance 的 tenant)。两表 colocate 同组后,该查询按 (tenant_id,id)
-- 路由必落本地分片,trigger 内无跨分片操作 → "unsafe" 在本系统语义下安全。
SET citus.enable_unsafe_triggers TO on;

-- ① 显式 LOCAL 例外(留在 coordinator,不分布)
--   worker_registry:平台基础设施,心跳高频 UPDATE,reference 的 2PC 写放大不可接受
--   process_staging:平台库遗留 WAP 表(业务库另有分区版),无租户分片价值
--   系统表 4 张 + flyway 历史:本就无租户维度
-- (LOCAL = 什么都不做)

-- ② reference 表:先于 distributed(distributed→reference 的 FK 要求目标先就位)
DO $$
DECLARE r RECORD;
BEGIN
  FOR r IN
    SELECT format('%I.%I', n.nspname, c.relname) AS tbl
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'batch' AND c.relkind = 'r'
      -- 有 tenant_id 列
      AND EXISTS (SELECT 1 FROM information_schema.columns ic
                  WHERE ic.table_schema = n.nspname AND ic.table_name = c.relname
                    AND ic.column_name = 'tenant_id')
      -- 但 PK 不含 tenant_id(未被 W1-W6 复合化 → 配置/字典类)
      AND NOT EXISTS (
        SELECT 1 FROM pg_index i
        CROSS JOIN LATERAL unnest(i.indkey::int[]) k(attnum)
        JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = k.attnum
        WHERE i.indrelid = c.oid AND i.indisprimary AND a.attname = 'tenant_id')
      -- 显式 LOCAL 例外
      AND c.relname NOT IN ('worker_registry', 'process_staging')
      -- 幂等:已分布的跳过
      AND NOT EXISTS (SELECT 1 FROM pg_dist_partition dp WHERE dp.logicalrelid = c.oid)
    ORDER BY c.relname
  LOOP
    RAISE NOTICE 'reference: %', r.tbl;
    PERFORM create_reference_table(r.tbl);
  END LOOP;
END$$;

-- ②.5 分区表出站 FK 暂卸(Citus 转换期对"分区表 FK→已分布表"的校验是跨节点
--   complex join,不支持;转换完成后在 ⑤ 原样加回——distributed 间 colocated 复合 FK
--   与 distributed→reference FK 均已 POC 验证可建)
ALTER TABLE batch.job_instance DROP CONSTRAINT IF EXISTS job_instance_trigger_request_id_fkey;
ALTER TABLE batch.job_instance DROP CONSTRAINT IF EXISTS job_instance_p_trigger_request_id_fkey;
ALTER TABLE batch.job_instance DROP CONSTRAINT IF EXISTS job_instance_job_definition_id_fkey;
ALTER TABLE batch.job_instance DROP CONSTRAINT IF EXISTS job_instance_p_job_definition_id_fkey;


-- ②.6 通用 FK 暂存:凡"双方都将被分布(PK 含 tenant_id)"的表间 FK,转换期一律暂卸,
--   定义存入 stash 表,⑤ 全量分布后按原定义重建(Citus 限制:distributed↔local 间不可有 FK)。
CREATE TABLE IF NOT EXISTS public.citus_fk_stash (
  conname text PRIMARY KEY, child_table text NOT NULL, condef text NOT NULL);
DO $$
DECLARE fk RECORD;
BEGIN
  FOR fk IN
    SELECT con.conname, con.conrelid::regclass::text AS child, pg_get_constraintdef(con.oid) AS def
    FROM pg_constraint con
    JOIN pg_class cc ON cc.oid = con.conrelid
    JOIN pg_class pc ON pc.oid = con.confrelid
    WHERE con.contype = 'f'
      AND cc.relnamespace = 'batch'::regnamespace AND pc.relnamespace = 'batch'::regnamespace
      -- 双方 PK 均含 tenant_id(= 双方都在 distributed 清单)
      AND EXISTS (SELECT 1 FROM pg_index i CROSS JOIN LATERAL unnest(i.indkey::int[]) k(a)
                  JOIN pg_attribute at ON at.attrelid=cc.oid AND at.attnum=k.a
                  WHERE i.indrelid=cc.oid AND i.indisprimary AND at.attname='tenant_id')
      AND EXISTS (SELECT 1 FROM pg_index i CROSS JOIN LATERAL unnest(i.indkey::int[]) k(a)
                  JOIN pg_attribute at ON at.attrelid=pc.oid AND at.attnum=k.a
                  WHERE i.indrelid=pc.oid AND i.indisprimary AND at.attname='tenant_id')
  LOOP
    INSERT INTO public.citus_fk_stash VALUES (fk.conname, fk.child, fk.def)
      ON CONFLICT (conname) DO NOTHING;
    EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', fk.child, fk.conname);
    RAISE NOTICE 'FK 暂卸: % on %', fk.conname, fk.child;
  END LOOP;
END$$;

-- ③ distributed 表:按 FK 依赖序逐条顶层执行。
--   每条 create_distributed_table 必须独立事务(Citus 限制:带 reference-FK 的表
--   不能在多语句事务里转换),故不用 DO 块循环,用临时幂等函数 + 逐条 SELECT。
--   锚 = trigger_request(job_instance 复合 FK 指向它,被引用方先分布);全员 colocate 同组。
CREATE OR REPLACE FUNCTION pg_temp.dist_one(tbl regclass, is_anchor boolean) RETURNS text AS $fn$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_dist_partition dp WHERE dp.logicalrelid = tbl) THEN
    RETURN 'skip(已分布): ' || tbl::text;
  END IF;
  IF is_anchor THEN
    PERFORM create_distributed_table(tbl::text, 'tenant_id');
  ELSE
    PERFORM create_distributed_table(tbl::text, 'tenant_id', colocate_with => 'batch.trigger_request');
  END IF;
  RETURN 'distributed: ' || tbl::text;
END$fn$ LANGUAGE plpgsql;

SELECT pg_temp.dist_one('batch.trigger_request', true);
SELECT pg_temp.dist_one('batch.job_instance', false);
SELECT pg_temp.dist_one('batch.job_partition', false);
SELECT pg_temp.dist_one('batch.job_task', false);
SELECT pg_temp.dist_one('batch.job_step_instance', false);
SELECT pg_temp.dist_one('batch.job_execution_log', false);
SELECT pg_temp.dist_one('batch.compensation_command', false);
SELECT pg_temp.dist_one('batch.compensation_checkpoint', false);
SELECT pg_temp.dist_one('batch.retry_schedule', false);
SELECT pg_temp.dist_one('batch.trigger_outbox_event', false);
SELECT pg_temp.dist_one('batch.trigger_misfire_pending', false);
SELECT pg_temp.dist_one('batch.trigger_runtime_state', false);
SELECT pg_temp.dist_one('batch.batch_day_instance', false);
SELECT pg_temp.dist_one('batch.batch_day_waiting_launch', false);
SELECT pg_temp.dist_one('batch.batch_day_replay_session', false);
SELECT pg_temp.dist_one('batch.batch_day_replay_entry', false);
SELECT pg_temp.dist_one('batch.batch_day_operation_audit', false);
SELECT pg_temp.dist_one('batch.tenant_scheduler_snapshot', false);
SELECT pg_temp.dist_one('batch.outbox_event', false);
SELECT pg_temp.dist_one('batch.event_delivery_log', false);
SELECT pg_temp.dist_one('batch.event_outbox_retry', false);
SELECT pg_temp.dist_one('batch.worker_report_outbox', false);
SELECT pg_temp.dist_one('batch.dead_letter_task', false);
SELECT pg_temp.dist_one('batch.file_record', false);
SELECT pg_temp.dist_one('batch.pipeline_instance', false);
SELECT pg_temp.dist_one('batch.file_dispatch_record', false);
SELECT pg_temp.dist_one('batch.file_error_record', false);
SELECT pg_temp.dist_one('batch.file_audit_log', false);
SELECT pg_temp.dist_one('batch.file_channel_health', false);
SELECT pg_temp.dist_one('batch.pipeline_progress', false);
SELECT pg_temp.dist_one('batch.pipeline_step_run', false);
SELECT pg_temp.dist_one('batch.workflow_run', false);
SELECT pg_temp.dist_one('batch.workflow_node_run', false);
SELECT pg_temp.dist_one('batch.approval_command', false);
SELECT pg_temp.dist_one('batch.console_operation_audit', false);
SELECT pg_temp.dist_one('batch.console_ai_audit_log', false);
SELECT pg_temp.dist_one('batch.console_push_subscription', false);
SELECT pg_temp.dist_one('batch.console_push_job_notification', false);
SELECT pg_temp.dist_one('batch.console_push_approval_notification', false);
SELECT pg_temp.dist_one('batch.notification_delivery_log', false);
SELECT pg_temp.dist_one('batch.webhook_delivery_log', false);
SELECT pg_temp.dist_one('batch.forensic_export_log', false);
SELECT pg_temp.dist_one('batch.alert_event', false);
SELECT pg_temp.dist_one('batch.data_quality_check', false);
SELECT pg_temp.dist_one('batch.idempotency_record', false);
SELECT pg_temp.dist_one('batch.quota_runtime_state', false);
SELECT pg_temp.dist_one('batch.result_version', false);
SELECT pg_temp.dist_one('batch.config_change_log', false);
SELECT pg_temp.dist_one('batch.config_sync_log', false);



-- ④.5 重建暂存 FK(全部分布完成,colocated 复合 FK 可建)
DO $$
DECLARE fk RECORD;
BEGIN
  FOR fk IN SELECT * FROM public.citus_fk_stash LOOP
    DECLARE
      def text := fk.condef;
      fkcols text;
      nontenant text;
    BEGIN
      -- Citus 禁止 SET NULL 触及分布列:复合 FK 含 tenant_id 时,
      -- 改写为 PG15+ 列级 SET NULL(只清非分布列),引用语义不变。
      IF def LIKE '%SET NULL%' AND def LIKE '%tenant_id%' THEN
        fkcols := (regexp_match(def, 'FOREIGN KEY \(([^)]*)\)'))[1];
        nontenant := trim(both ', ' from replace(replace(fkcols, 'tenant_id,', ''), 'tenant_id', ''));
        def := replace(def, 'ON DELETE SET NULL', format('ON DELETE SET NULL (%s)', nontenant));
      END IF;
      BEGIN
        EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I %s', fk.child_table, fk.conname, def);
        RAISE NOTICE 'FK 重建: % on %', fk.conname, fk.child_table;
      EXCEPTION WHEN OTHERS THEN
        -- Citus 不支持的形态(实测:SET NULL 含分布列,连列级形态也拒)→ 转应用层守护,
        -- 与 V171 既定先例一致(SuccessInstanceArchiveScheduler 级联删兜底)。
        RAISE NOTICE 'FK 转应用层守护(Citus 拒绝): % on % — %', fk.conname, fk.child_table, SQLERRM;
      END;
    END;
  END LOOP;
  DELETE FROM public.citus_fk_stash;
END$$;
DROP TABLE IF EXISTS public.citus_fk_stash;

-- ④ 加回 ②.5 暂卸的分区表出站 FK(此时双方均已分布/为 reference)
ALTER TABLE batch.job_instance ADD CONSTRAINT job_instance_trigger_request_id_fkey
    FOREIGN KEY (tenant_id, trigger_request_id) REFERENCES batch.trigger_request (tenant_id, id);

-- ⑤ 完整性自检:不应残留"有 tenant_id 列却仍是普通表"的非例外表
DO $$
DECLARE leftover INT;
BEGIN
  SELECT count(*) INTO leftover
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname = 'batch' AND c.relkind IN ('r','p')
    AND EXISTS (SELECT 1 FROM information_schema.columns ic
                WHERE ic.table_schema='batch' AND ic.table_name=c.relname AND ic.column_name='tenant_id')
    AND NOT EXISTS (SELECT 1 FROM pg_dist_partition dp WHERE dp.logicalrelid = c.oid)
    AND c.relname NOT IN ('worker_registry', 'process_staging')
    -- 分区子表跟随父表,不单独出现在 pg_dist_partition 的检查里按父表计
    AND NOT EXISTS (SELECT 1 FROM pg_inherits ih WHERE ih.inhrelid = c.oid);
  IF leftover > 0 THEN
    RAISE EXCEPTION 'distribute 不完整:% 张带 tenant_id 的表未被分类(见 NOTICE 清单核对)', leftover;
  END IF;
  RAISE NOTICE 'distribute 完整性自检通过';
END$$;

\echo '=== 分布结果汇总 ==='
SELECT
  CASE WHEN partmethod = 'n' THEN 'reference' ELSE 'distributed' END AS kind,
  count(*) AS tables
FROM pg_dist_partition GROUP BY 1 ORDER BY 1;
