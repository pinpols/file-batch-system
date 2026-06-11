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
    ORDER BY c.relname
  LOOP
    RAISE NOTICE 'reference: %', r.tbl;
    PERFORM create_reference_table(r.tbl);
  END LOOP;
END$$;

-- ③ distributed 表:按 FK 依赖序显式排列(父先子后;锚表 job_instance 定义 colocation 组)。
--   清单 = W1-W6 全部复合 PK 表(分区父表 outbox_event/job_instance 由 Citus 原生支持)。
DO $$
DECLARE
  t TEXT;
  anchor CONSTANT TEXT := 'batch.job_instance';
  ordered TEXT[] := ARRAY[
    -- 锚(分区父表,Citus 支持分布分区表)
    'batch.job_instance',
    -- W1 job 簇(父先子后:partition → task → step;其余孤立)
    'batch.job_partition', 'batch.job_task', 'batch.job_step_instance',
    'batch.job_execution_log', 'batch.compensation_command',
    'batch.compensation_checkpoint', 'batch.retry_schedule',
    -- W2 trigger/batch_day 簇(replay_session 先于 replay_entry)
    'batch.trigger_request', 'batch.trigger_outbox_event', 'batch.trigger_misfire_pending',
    'batch.trigger_runtime_state', 'batch.batch_day_instance', 'batch.batch_day_waiting_launch',
    'batch.batch_day_replay_session', 'batch.batch_day_replay_entry',
    'batch.batch_day_operation_audit', 'batch.tenant_scheduler_snapshot',
    -- W3 outbox 簇(outbox_event 为分区父表)
    'batch.outbox_event', 'batch.event_delivery_log', 'batch.event_outbox_retry',
    'batch.worker_report_outbox', 'batch.dead_letter_task',
    -- W4 file/pipeline 簇(file_record/pipeline_instance 先于其子)
    'batch.file_record', 'batch.pipeline_instance', 'batch.file_dispatch_record',
    'batch.file_error_record', 'batch.file_audit_log', 'batch.file_channel_health',
    'batch.pipeline_progress', 'batch.pipeline_step_run',
    -- W5 workflow 簇(run 先于 node_run)
    'batch.workflow_run', 'batch.workflow_node_run', 'batch.approval_command',
    -- W6 audit/notify 散表
    'batch.console_operation_audit', 'batch.console_ai_audit_log',
    'batch.console_push_subscription', 'batch.console_push_job_notification',
    'batch.console_push_approval_notification', 'batch.notification_delivery_log',
    'batch.webhook_delivery_log', 'batch.forensic_export_log', 'batch.alert_event',
    'batch.data_quality_check', 'batch.idempotency_record', 'batch.quota_runtime_state',
    'batch.result_version', 'batch.config_change_log', 'batch.config_sync_log'
  ];
BEGIN
  FOREACH t IN ARRAY ordered LOOP
    RAISE NOTICE 'distributed: %', t;
    IF t = anchor THEN
      PERFORM create_distributed_table(t, 'tenant_id');
    ELSE
      PERFORM create_distributed_table(t, 'tenant_id', colocate_with => anchor);
    END IF;
  END LOOP;
END$$;

-- ④ 完整性自检:不应残留"有 tenant_id 列却仍是普通表"的非例外表
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
