-- =========================================================
-- cleanup-historical-failures.sql
-- 清理累积的历史失败数据（FAILED / CANCELLED / TERMINATED job_instance 及其级联），
-- 保留 SUCCESS + 最近 1h 内的所有状态（便于调试）。
-- 本入口不复制冷表；带 checkpoint 的候选必须走 Orchestrator 归档，任何写入前拒绝。
--
-- 用法：
--   PGPASSWORD=... psql -h localhost -p 15432 -U batch_user -d batch_platform \
--     -v ON_ERROR_STOP=1 -f scripts/db/cleanup-historical-failures.sql
--
-- 调整保留窗口：修改 `interval '1 hour'` 为你想保留的时间长度。
--
-- ⚠️ 会真实删除数据，不可回滚（除非事务中断）。生产环境请先快照。
-- =========================================================

\set ON_ERROR_STOP on
\if :{?retention_interval}
\else
  \set retention_interval '1 hour'
\endif

-- 位点需要与完整运行树一起归档；此独立清理入口不提供冷复制，遇到位点先拒绝。
SELECT EXISTS (
  SELECT 1 FROM batch.pipeline_progress progress
  JOIN batch.pipeline_instance pipeline
    ON pipeline.id = progress.pipeline_instance_id AND pipeline.tenant_id = progress.tenant_id
  JOIN batch.job_instance job
    ON job.id = pipeline.related_job_instance_id AND job.tenant_id = pipeline.tenant_id
  WHERE job.instance_status IN ('FAILED','CANCELLED','TERMINATED')
    AND job.created_at < now() - interval :'retention_interval'
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
  WHERE job.instance_status IN ('FAILED','CANCELLED','TERMINATED') AND job.created_at < now() - :'retention_interval'::interval
) AS _cleanup_has_quality_evidence \gset
\if :_cleanup_has_quality_evidence
  DO $quality_cleanup_guard$
  BEGIN
    RAISE EXCEPTION 'quality-evidence-bearing instances require the Orchestrator archive service; no data deleted.'
      USING ERRCODE = '55000';
  END
  $quality_cleanup_guard$;
\endif

-- 不会扩大对该登记表的访问范围。
SELECT EXISTS (
  SELECT 1 FROM batch.job_instance job
  WHERE job.instance_status IN ('FAILED','CANCELLED','TERMINATED') AND job.created_at < now() - :'retention_interval'::interval
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

-- 1) 深度级联：从叶子向根删
-- job_step_instance 依赖 job_partition
WITH old_instances AS (
  SELECT id FROM batch.job_instance
   WHERE instance_status IN ('FAILED','CANCELLED','TERMINATED')
     AND created_at < now() - interval :'retention_interval'
),
old_partitions AS (
  SELECT id FROM batch.job_partition
   WHERE job_instance_id IN (SELECT id FROM old_instances)
)
DELETE FROM batch.job_step_instance
 WHERE job_partition_id IN (SELECT id FROM old_partitions);

-- job_task 依赖 job_instance
WITH old_instances AS (
  SELECT id FROM batch.job_instance
   WHERE instance_status IN ('FAILED','CANCELLED','TERMINATED')
     AND created_at < now() - interval :'retention_interval'
)
DELETE FROM batch.job_task WHERE job_instance_id IN (SELECT id FROM old_instances);

-- pipeline_step_run 依赖 pipeline_instance
WITH old_instances AS (
  SELECT id FROM batch.job_instance
   WHERE instance_status IN ('FAILED','CANCELLED','TERMINATED')
     AND created_at < now() - interval :'retention_interval'
),
old_pipeline_instances AS (
  SELECT id FROM batch.pipeline_instance
   WHERE related_job_instance_id IN (SELECT id FROM old_instances)
)
DELETE FROM batch.pipeline_step_run
 WHERE pipeline_instance_id IN (SELECT id FROM old_pipeline_instances);

-- file_dispatch_record 依赖 pipeline_instance
WITH old_instances AS (
  SELECT id FROM batch.job_instance
   WHERE instance_status IN ('FAILED','CANCELLED','TERMINATED')
     AND created_at < now() - interval :'retention_interval'
),
old_pipeline_ids AS (
  SELECT id FROM batch.pipeline_instance
   WHERE related_job_instance_id IN (SELECT id FROM old_instances)
)
DELETE FROM batch.file_dispatch_record
 WHERE pipeline_instance_id IN (SELECT id FROM old_pipeline_ids);

-- pipeline_instance 依赖 job_instance
WITH old_instances AS (
  SELECT id FROM batch.job_instance
   WHERE instance_status IN ('FAILED','CANCELLED','TERMINATED')
     AND created_at < now() - interval :'retention_interval'
)
DELETE FROM batch.pipeline_instance
 WHERE related_job_instance_id IN (SELECT id FROM old_instances);

-- dead_letter_task 引用 partition
WITH old_partitions AS (
  SELECT p.id FROM batch.job_partition p
  JOIN batch.job_instance i ON i.id = p.job_instance_id
   WHERE i.instance_status IN ('FAILED','CANCELLED','TERMINATED')
     AND i.created_at < now() - interval :'retention_interval'
)
DELETE FROM batch.dead_letter_task
 WHERE source_type = 'JOB_PARTITION'
   AND source_id IN (SELECT id FROM old_partitions);

-- job_execution_log 依赖 job_instance（必须早于 job_partition：V119 之前 job_execution_log.job_partition_id 无级联）
WITH old_instances AS (
  SELECT id FROM batch.job_instance
   WHERE instance_status IN ('FAILED','CANCELLED','TERMINATED')
     AND created_at < now() - interval :'retention_interval'
)
DELETE FROM batch.job_execution_log WHERE job_instance_id IN (SELECT id FROM old_instances);

-- job_partition
WITH old_instances AS (
  SELECT id FROM batch.job_instance
   WHERE instance_status IN ('FAILED','CANCELLED','TERMINATED')
     AND created_at < now() - interval :'retention_interval'
)
DELETE FROM batch.job_partition WHERE job_instance_id IN (SELECT id FROM old_instances);

-- workflow_node_run + workflow_run（关联到 instance 的）
WITH old_instances AS (
  SELECT id FROM batch.job_instance
   WHERE instance_status IN ('FAILED','CANCELLED','TERMINATED')
     AND created_at < now() - interval :'retention_interval'
),
old_wf_runs AS (
  SELECT id FROM batch.workflow_run
   WHERE related_job_instance_id IN (SELECT id FROM old_instances)
)
DELETE FROM batch.workflow_node_run
 WHERE workflow_run_id IN (SELECT id FROM old_wf_runs);

WITH old_instances AS (
  SELECT id FROM batch.job_instance
   WHERE instance_status IN ('FAILED','CANCELLED','TERMINATED')
     AND created_at < now() - interval :'retention_interval'
)
DELETE FROM batch.workflow_run
 WHERE related_job_instance_id IN (SELECT id FROM old_instances);

-- 作业监控 claim 不设 job_instance 外键（源表按业务日分区）；删除实例前显式清理。
WITH old_instances AS (
  SELECT id FROM batch.job_instance
   WHERE instance_status IN ('FAILED','CANCELLED','TERMINATED')
     AND created_at < now() - interval :'retention_interval'
)
DELETE FROM batch.job_monitoring_alert_claim
 WHERE job_instance_id IN (SELECT id FROM old_instances);

-- 最后根：job_instance
DELETE FROM batch.job_instance
 WHERE instance_status IN ('FAILED','CANCELLED','TERMINATED')
   AND created_at < now() - interval :'retention_interval';

-- 2) 请求/等待保留：FORWARD_FAILED可恢复，不能按终态清；保留未传播拒绝及冷热引用。
SELECT now() - :'retention_interval'::interval AS request_retention_cutoff \gset
\set request_cleanup_mode failure
\ir cleanup-trigger-request-retention.sql

-- 3) outbox：已发布 > 24h 的删（保留近期事件供调试）
--    注意：先删 event_delivery_log（FK 引用 outbox_event.id）再删 outbox_event 本身
DELETE FROM batch.event_delivery_log
 WHERE outbox_event_id IN (
   SELECT id FROM batch.outbox_event
    WHERE publish_status = 'PUBLISHED'
      AND created_at < now() - interval '24 hours'
 );

DELETE FROM batch.outbox_event
 WHERE publish_status = 'PUBLISHED'
   AND created_at < now() - interval '24 hours';

-- 统计
SELECT 'instance FAILED/CANCELLED/TERMINATED remaining', count(*)
  FROM batch.job_instance WHERE instance_status IN ('FAILED','CANCELLED','TERMINATED');
SELECT 'dead_letter_task NEW remaining', count(*) FROM batch.dead_letter_task WHERE replay_status='NEW';
SELECT 'outbox PUBLISHED remaining', count(*) FROM batch.outbox_event WHERE publish_status='PUBLISHED';
SELECT 'SUCCESS instances kept', count(*) FROM batch.job_instance WHERE instance_status='SUCCESS';

COMMIT;
