-- =========================================================
-- cleanup-success-instances.sql
-- 直接清理历史成功的 job_instance 及其级联数据（本脚本不复制冷表）。和 cleanup-historical-failures.sql 互补：
--   该脚本    清理 FAILED / CANCELLED / TERMINATED（默认 1h 保留窗口，事件复盘用）
--   本脚本    清理 SUCCESS（默认 30d 保留窗口，长归档）
--
-- 保留策略（可通过 psql -v 覆盖）：
--   SUCCESS / PARTIAL_FAILED 实例保留 :success_retention_days 天（默认 30 天）
--   带 checkpoint 的实例必须走 Orchestrator 完整归档，本入口在任何删除前拒绝。
--
-- 用法：
--   PGPASSWORD=... psql -h localhost -p 15432 -U batch_user -d batch_platform \
--     -v ON_ERROR_STOP=1 -f scripts/db/cleanup-success-instances.sql
--
-- 自定义保留窗口：
--   psql ... -v success_retention_days=14 -f ...
--
-- 推荐运维频率：每周一次（凌晨低峰期）。Cron 例：
--   0 4 * * 0  PGPASSWORD=$PGPW psql -h $PGHOST -U $PGUSER -d batch_platform \
--               -v ON_ERROR_STOP=1 -f /opt/batch/scripts/db/cleanup-success-instances.sql
--
-- ⚠️ 真删除，不可回滚（除非事务中断）。生产环境请先快照或先做 archive schema 备份。
--
-- 容量估算：每天 100 万 SUCCESS instance 保留 30 天 = 3000 万行 + 关联级联（partition × N、
-- task / step / pipeline_run / file_record 等，估 5-10 倍放大），单 PG 实例还能扛但
-- 接近上限。海量场景需要分库分表 + archive schema 双层（参考 scalability-assessment.md
-- §6 Phase 3）。
-- =========================================================

\set ON_ERROR_STOP on

\if :{?success_retention_days}
\else
  \set success_retention_days 30
\endif

\echo '=== job_instance 清理前统计（按状态） ==='
SELECT instance_status, count(*) AS rows
FROM batch.job_instance
GROUP BY instance_status
ORDER BY rows DESC;

-- 位点需要与完整运行树一起归档；此独立清理入口不提供冷复制，遇到位点先拒绝。
SELECT EXISTS (
  SELECT 1 FROM batch.pipeline_progress progress
  JOIN batch.pipeline_instance pipeline
    ON pipeline.id = progress.pipeline_instance_id AND pipeline.tenant_id = progress.tenant_id
  JOIN batch.job_instance job
    ON job.id = pipeline.related_job_instance_id AND job.tenant_id = pipeline.tenant_id
  WHERE job.instance_status IN ('SUCCESS','PARTIAL_FAILED')
    AND job.finished_at < now() - (:success_retention_days || ' days')::interval
) AS _cleanup_has_checkpoint \gset
\if :_cleanup_has_checkpoint
  DO $checkpoint_cleanup_guard$
  BEGIN
    RAISE EXCEPTION 'checkpoint-bearing instances require the Orchestrator archive service; no data deleted.'
      USING ERRCODE = '55000';
  END
  $checkpoint_cleanup_guard$;
\endif

-- DQ执行证据必须随完整运行树原子搬运；此入口不提供冷复制，删除前失败关闭。
SELECT EXISTS (
  SELECT 1 FROM batch.data_quality_check check_row
  JOIN batch.job_instance job ON job.id = check_row.job_instance_id
  WHERE job.instance_status IN ('SUCCESS','PARTIAL_FAILED') AND job.finished_at < now() - (:'success_retention_days' || ' days')::interval
) AS _cleanup_has_quality_evidence \gset
\if :_cleanup_has_quality_evidence
  DO $quality_cleanup_guard$
  BEGIN
    RAISE EXCEPTION 'quality-evidence-bearing instances require the Orchestrator archive service; no data deleted.'
      USING ERRCODE = '55000';
  END
  $quality_cleanup_guard$;
\endif

-- 关联的运行数据属于完整归档事务，其中包括冷存储中的 Workflow 血缘。
SELECT EXISTS (
  SELECT 1 FROM batch.job_instance job
  WHERE job.instance_status IN ('SUCCESS','PARTIAL_FAILED') AND job.finished_at < now() - (:'success_retention_days' || ' days')::interval
    AND (EXISTS (SELECT 1 FROM batch.workflow_node_run node WHERE node.job_instance_id=job.id)
         OR EXISTS (SELECT 1 FROM archive.workflow_node_run_archive node WHERE node.job_instance_id=job.id)
         OR EXISTS (SELECT 1 FROM batch.workflow_run run WHERE run.related_job_instance_id=job.id)
         OR EXISTS (SELECT 1 FROM archive.workflow_run_archive run WHERE run.related_job_instance_id=job.id))
) AS _cleanup_has_workflow_reference \gset
\if :_cleanup_has_workflow_reference
  DO $runtime_cleanup_guard$ BEGIN
    RAISE EXCEPTION 'Workflow-linked instances require the Orchestrator archive service; no data deleted.'
      USING ERRCODE='55000';
  END $runtime_cleanup_guard$;
\endif

BEGIN;

-- 1) job_step_instance（依赖 job_partition.job_instance_id 间接）
WITH old_instances AS (
  SELECT id FROM batch.job_instance
   WHERE instance_status IN ('SUCCESS','PARTIAL_FAILED')
     AND finished_at < now() - (:success_retention_days || ' days')::interval
),
old_partitions AS (
  SELECT id FROM batch.job_partition
   WHERE job_instance_id IN (SELECT id FROM old_instances)
)
DELETE FROM batch.job_step_instance
 WHERE job_partition_id IN (SELECT id FROM old_partitions);

-- 2) job_task → job_instance
WITH old_instances AS (
  SELECT id FROM batch.job_instance
   WHERE instance_status IN ('SUCCESS','PARTIAL_FAILED')
     AND finished_at < now() - (:success_retention_days || ' days')::interval
)
DELETE FROM batch.job_task WHERE job_instance_id IN (SELECT id FROM old_instances);

-- 3) pipeline_step_run → pipeline_instance
WITH old_instances AS (
  SELECT id FROM batch.job_instance
   WHERE instance_status IN ('SUCCESS','PARTIAL_FAILED')
     AND finished_at < now() - (:success_retention_days || ' days')::interval
),
old_pipeline_instances AS (
  SELECT id FROM batch.pipeline_instance
   WHERE related_job_instance_id IN (SELECT id FROM old_instances)
)
DELETE FROM batch.pipeline_step_run
 WHERE pipeline_instance_id IN (SELECT id FROM old_pipeline_instances);

-- 4) pipeline_instance（先解 file_record FK 引用 + 删 file_dispatch_record 子表避免冲突）
WITH old_instances AS (
  SELECT id FROM batch.job_instance
   WHERE instance_status IN ('SUCCESS','PARTIAL_FAILED')
     AND finished_at < now() - (:success_retention_days || ' days')::interval
)
UPDATE batch.pipeline_instance SET file_id = NULL
 WHERE related_job_instance_id IN (SELECT id FROM old_instances);

-- file_dispatch_record FK 到 pipeline_instance（dispatch 链路落盘时写）
WITH old_instances AS (
  SELECT id FROM batch.job_instance
   WHERE instance_status IN ('SUCCESS','PARTIAL_FAILED')
     AND finished_at < now() - (:success_retention_days || ' days')::interval
),
old_pipeline_instances AS (
  SELECT id FROM batch.pipeline_instance
   WHERE related_job_instance_id IN (SELECT id FROM old_instances)
)
DELETE FROM batch.file_dispatch_record
 WHERE pipeline_instance_id IN (SELECT id FROM old_pipeline_instances);

WITH old_instances AS (
  SELECT id FROM batch.job_instance
   WHERE instance_status IN ('SUCCESS','PARTIAL_FAILED')
     AND finished_at < now() - (:success_retention_days || ' days')::interval
)
DELETE FROM batch.pipeline_instance
 WHERE related_job_instance_id IN (SELECT id FROM old_instances);

-- 5) job_execution_log（必须早于 job_partition：V119 之前 job_execution_log.job_partition_id 无级联）
WITH old_instances AS (
  SELECT id FROM batch.job_instance
   WHERE instance_status IN ('SUCCESS','PARTIAL_FAILED')
     AND finished_at < now() - (:success_retention_days || ' days')::interval
)
DELETE FROM batch.job_execution_log WHERE job_instance_id IN (SELECT id FROM old_instances);

-- 6) job_partition → job_instance
WITH old_instances AS (
  SELECT id FROM batch.job_instance
   WHERE instance_status IN ('SUCCESS','PARTIAL_FAILED')
     AND finished_at < now() - (:success_retention_days || ' days')::interval
)
DELETE FROM batch.job_partition WHERE job_instance_id IN (SELECT id FROM old_instances);

-- 7) workflow_node_run → workflow_run → job_instance
WITH old_instances AS (
  SELECT id FROM batch.job_instance
   WHERE instance_status IN ('SUCCESS','PARTIAL_FAILED')
     AND finished_at < now() - (:success_retention_days || ' days')::interval
),
old_wf_runs AS (
  SELECT id FROM batch.workflow_run
   WHERE related_job_instance_id IN (SELECT id FROM old_instances)
)
DELETE FROM batch.workflow_node_run WHERE workflow_run_id IN (SELECT id FROM old_wf_runs);

WITH old_instances AS (
  SELECT id FROM batch.job_instance
   WHERE instance_status IN ('SUCCESS','PARTIAL_FAILED')
     AND finished_at < now() - (:success_retention_days || ' days')::interval
)
DELETE FROM batch.workflow_run WHERE related_job_instance_id IN (SELECT id FROM old_instances);

-- 7.5) compensation_command FK 引用：补偿命令记录与 job_instance 关联
WITH old_instances AS (
  SELECT id FROM batch.job_instance
   WHERE instance_status IN ('SUCCESS','PARTIAL_FAILED')
     AND finished_at < now() - (:success_retention_days || ' days')::interval
)
DELETE FROM batch.compensation_command
 WHERE related_job_instance_id IN (SELECT id FROM old_instances);

-- 7.6) 清理作业监控告警幂等 claim；归档后不再扫描热表实例，claim 无需继续保留。
WITH old_instances AS (
  SELECT id FROM batch.job_instance
   WHERE instance_status IN ('SUCCESS','PARTIAL_FAILED')
     AND finished_at < now() - (:success_retention_days || ' days')::interval
)
DELETE FROM batch.job_monitoring_alert_claim
 WHERE job_instance_id IN (SELECT id FROM old_instances);

-- 7.7) 解开 job_instance 自引用 FK：子实例的 parent_instance_id 指向即将被删的父
UPDATE batch.job_instance SET parent_instance_id = NULL
 WHERE parent_instance_id IN (
   SELECT id FROM batch.job_instance
    WHERE instance_status IN ('SUCCESS','PARTIAL_FAILED')
      AND finished_at < now() - (:success_retention_days || ' days')::interval
 );

-- 8) 根：job_instance
DELETE FROM batch.job_instance
 WHERE instance_status IN ('SUCCESS','PARTIAL_FAILED')
   AND finished_at < now() - (:success_retention_days || ' days')::interval;

-- 9) 请求/等待保留：保留冷热运行、活跃等待及其它账本证据；显式先子后父。
SELECT now() - (:'success_retention_days' || ' days')::interval AS request_retention_cutoff \gset
\set request_cleanup_mode success
\ir cleanup-trigger-request-retention.sql

COMMIT;

\echo '=== job_instance 清理后统计 ==='
SELECT instance_status, count(*) AS rows
FROM batch.job_instance
GROUP BY instance_status
ORDER BY rows DESC;

\echo '=== 表/索引大小 ==='
SELECT relname, pg_size_pretty(pg_total_relation_size('batch.' || relname)) AS total_size
FROM (VALUES
  ('job_instance'),('job_partition'),('job_task'),('job_step_instance'),
  ('pipeline_instance'),('pipeline_step_run'),
  ('workflow_run'),('workflow_node_run'),('job_execution_log')
) t(relname);
