-- =========================================================
-- cleanup-historical-failures.sql
-- 清理累积的历史失败数据（FAILED / CANCELLED / TERMINATED job_instance 及其级联），
-- 保留 SUCCESS + 最近 1h 内的所有状态（便于调试）。
--
-- 用法：
--   PGPASSWORD=... psql -h localhost -p 15432 -U batch_user -d batch_platform \
--     -v ON_ERROR_STOP=1 -f scripts/db/cleanup-historical-failures.sql
--
-- 调整保留窗口：修改 `interval '1 hour'` 为你想保留的时间长度。
--
-- ⚠️ 会真实删除数据，不可回滚（除非事务中断）。生产环境请先快照。
-- =========================================================

\set retention_interval '1 hour'

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

-- 最后根：job_instance
DELETE FROM batch.job_instance
 WHERE instance_status IN ('FAILED','CANCELLED','TERMINATED')
   AND created_at < now() - interval :'retention_interval';

-- 2) 孤儿 trigger_request：已被拒绝/转发失败/放弃/已 launch 但没有活跃 instance 引用
--   （job_instance.trigger_request_id 是 FK，不能删仍有 SUCCESS 实例引用的行）
DELETE FROM batch.trigger_request
 WHERE request_status IN ('REJECTED','FORWARD_FAILED','GIVE_UP','LAUNCHED')
   AND created_at < now() - interval :'retention_interval'
   AND id NOT IN (SELECT trigger_request_id FROM batch.job_instance WHERE trigger_request_id IS NOT NULL);

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
