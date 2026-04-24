-- cleanup-orphan-general-job.sql
-- ---------------------------------------------------------------
-- 用途：清理 default-tenant/gen_data_cleanup 这个孤儿 job_definition 及其
--       挂在 WaitingPartitionDispatchScheduler 上的 2 条历史 WAITING partition。
--
-- 背景：worker_registry 只有 IMPORT / EXPORT / DISPATCH 三组 worker，
--       没有 worker_group='GENERAL' 的 worker 在线；但 gen_data_cleanup 要求
--       GENERAL 组。结果每 10s 一次的 WaitingPartitionDispatchScheduler tick
--       都选不到 worker，刷出 `no_online_workers_in_group` WARN
--       （单次排查期间累计 1073 条）。
--
-- 幂等：所有 UPDATE 都带 "当前状态" 约束，跑过一次后再跑无副作用。
-- 可回滚：禁用 / CANCEL 均可用 reopen 反向（见文末）。
-- ---------------------------------------------------------------

\set ON_ERROR_STOP on
BEGIN;

-- 1) 禁用 job_definition：阻止新触发产生 WAITING partition
UPDATE batch.job_definition
SET enabled = false, updated_at = now()
WHERE tenant_id = 'default-tenant'
  AND job_code = 'gen_data_cleanup'
  AND enabled = true;

-- 2) 查看待处理的 WAITING partition（诊断用）
SELECT id, tenant_id, job_instance_id, partition_no, partition_status,
       worker_group, created_at
FROM batch.job_partition
WHERE tenant_id = 'default-tenant'
  AND partition_status = 'WAITING'
  AND worker_group = 'GENERAL';

-- 3) 把历史 WAITING partition 标 CANCELLED（停止 scheduler 选它们）
UPDATE batch.job_partition
SET partition_status = 'CANCELLED',
    finished_at = now(),
    updated_at = now(),
    version = version + 1
WHERE tenant_id = 'default-tenant'
  AND partition_status = 'WAITING'
  AND worker_group = 'GENERAL'
  AND job_instance_id IN (
    SELECT id FROM batch.job_instance
    WHERE tenant_id = 'default-tenant'
      AND job_code = 'gen_data_cleanup'
      AND instance_status IN ('CREATED','WAITING','READY','RUNNING')
  );

-- 4) 把对应 job_instance 标 CANCELLED
UPDATE batch.job_instance
SET instance_status = 'CANCELLED',
    finished_at = now()
WHERE tenant_id = 'default-tenant'
  AND job_code = 'gen_data_cleanup'
  AND instance_status IN ('CREATED','WAITING','READY','RUNNING');

COMMIT;

-- ---------------------------------------------------------------
-- 反向回滚（仅在决定恢复该 job 时使用，非日常操作）：
--
--   UPDATE batch.job_definition
--   SET enabled = true, updated_at = now()
--   WHERE tenant_id='default-tenant' AND job_code='gen_data_cleanup';
--
--   -- 历史 CANCELLED partition 不要重置为 WAITING（无此语义），
--   -- 想重跑请走 POST /api/console/instances/{id}/cancel 的反向接口或
--   -- 重新触发一次新的 job_instance。
-- ---------------------------------------------------------------
