-- =========================================================
-- cleanup-success-instances.sql
-- 归档历史成功的 job_instance 及其级联数据。和 cleanup-historical-failures.sql 互补：
--   该脚本    清理 FAILED / CANCELLED / TERMINATED（默认 1h 保留窗口，事件复盘用）
--   本脚本    清理 SUCCESS（默认 30d 保留窗口，长归档）
--
-- 保留策略（可通过 psql -v 覆盖）：
--   SUCCESS / PARTIAL_FAILED 实例保留 :success_retention_days 天（默认 30 天）
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

-- 5) job_partition → job_instance
WITH old_instances AS (
  SELECT id FROM batch.job_instance
   WHERE instance_status IN ('SUCCESS','PARTIAL_FAILED')
     AND finished_at < now() - (:success_retention_days || ' days')::interval
)
DELETE FROM batch.job_partition WHERE job_instance_id IN (SELECT id FROM old_instances);

-- 6) workflow_node_run → workflow_run → job_instance
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

-- 7) job_execution_log
WITH old_instances AS (
  SELECT id FROM batch.job_instance
   WHERE instance_status IN ('SUCCESS','PARTIAL_FAILED')
     AND finished_at < now() - (:success_retention_days || ' days')::interval
)
DELETE FROM batch.job_execution_log WHERE job_instance_id IN (SELECT id FROM old_instances);

-- 7.5) compensation_command FK 引用：补偿命令记录与 job_instance 关联
WITH old_instances AS (
  SELECT id FROM batch.job_instance
   WHERE instance_status IN ('SUCCESS','PARTIAL_FAILED')
     AND finished_at < now() - (:success_retention_days || ' days')::interval
)
DELETE FROM batch.compensation_command
 WHERE related_job_instance_id IN (SELECT id FROM old_instances);

-- 7.6) 解开 job_instance 自引用 FK：子实例的 parent_instance_id 指向即将被删的父
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

-- 9) 孤儿 trigger_request（已被引用消失的可清）
DELETE FROM batch.trigger_request
 WHERE request_status IN ('LAUNCHED')
   AND created_at < now() - (:success_retention_days || ' days')::interval
   AND id NOT IN (SELECT trigger_request_id FROM batch.job_instance WHERE trigger_request_id IS NOT NULL);

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
