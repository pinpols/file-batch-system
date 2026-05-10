-- =========================================================
-- V119 - 给 job_partition 上剩余两个未级联的子表 FK 加 ON DELETE CASCADE
-- 背景：V58 只覆盖 job_task / job_step_instance.job_task_id，遗漏：
--   1) job_execution_log.job_partition_id      (V7 创建，无级联)
--   2) job_step_instance.job_partition_id      (V13 创建，无级联；V58 仅级联了 job_task_id)
--
-- 现象（2026-05-10 04:30 orchestrator.log）：
--   SuccessInstanceArchiveService.archiveOnce() 删 job_partition 时仍被
--   job_execution_log 引用 → DataIntegrityViolationException → 整个事务回滚
--   每个 cleanup tick 都重复失败。
--
-- 防御层 1：Service 已把 deleteJobExecutionLogsByInstanceIds 提前到
-- deleteJobPartitionsByInstanceIds 之前（同 commit）。
-- 防御层 2：本迁移加 CASCADE 兜底，保证未来即便 cleanup 序列遗漏也不至于卡住整个调度。
-- =========================================================

ALTER TABLE batch.job_execution_log
    DROP CONSTRAINT IF EXISTS job_execution_log_job_partition_id_fkey,
    ADD CONSTRAINT job_execution_log_job_partition_id_fkey
        FOREIGN KEY (job_partition_id) REFERENCES batch.job_partition(id) ON DELETE CASCADE;

ALTER TABLE batch.job_step_instance
    DROP CONSTRAINT IF EXISTS job_step_instance_job_partition_id_fkey,
    ADD CONSTRAINT job_step_instance_job_partition_id_fkey
        FOREIGN KEY (job_partition_id) REFERENCES batch.job_partition(id) ON DELETE CASCADE;
