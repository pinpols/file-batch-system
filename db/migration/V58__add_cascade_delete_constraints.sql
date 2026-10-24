-- =========================================================
-- V58：为运行态表外键增加 ON DELETE CASCADE。
-- 说明：
-- 1) job_partition.job_instance_id -> job_instance.id
-- 2) job_task.job_partition_id -> job_partition.id
-- 3) job_step_instance.job_task_id -> job_task.id
-- =========================================================

-- job_partition.job_instance_id（原外键来自 V5）

ALTER TABLE batch.job_partition
    DROP CONSTRAINT IF EXISTS job_partition_job_instance_id_fkey,
    ADD CONSTRAINT job_partition_job_instance_id_fkey
        FOREIGN KEY (job_instance_id) REFERENCES batch.job_instance(id) ON DELETE CASCADE;

-- job_task.job_partition_id（原外键来自 V5）

ALTER TABLE batch.job_task
    DROP CONSTRAINT IF EXISTS job_task_job_partition_id_fkey,
    ADD CONSTRAINT job_task_job_partition_id_fkey
        FOREIGN KEY (job_partition_id) REFERENCES batch.job_partition(id) ON DELETE CASCADE;

-- job_step_instance.job_task_id（原外键来自 V13）

ALTER TABLE batch.job_step_instance
    DROP CONSTRAINT IF EXISTS job_step_instance_job_task_id_fkey,
    ADD CONSTRAINT job_step_instance_job_task_id_fkey
        FOREIGN KEY (job_task_id) REFERENCES batch.job_task(id) ON DELETE CASCADE;
