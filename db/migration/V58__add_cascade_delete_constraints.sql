-- =========================================================
-- V58 - Add ON DELETE CASCADE to runtime table foreign keys
-- Notes:
-- 1) job_partition.job_instance_id -> job_instance.id
-- 2) job_task.job_partition_id -> job_partition.id
-- 3) job_step_instance.job_task_id -> job_task.id
-- =========================================================

-- job_partition.job_instance_id (original FK from V5)
ALTER TABLE batch.job_partition
    DROP CONSTRAINT IF EXISTS job_partition_job_instance_id_fkey,
    ADD CONSTRAINT job_partition_job_instance_id_fkey
        FOREIGN KEY (job_instance_id) REFERENCES batch.job_instance(id) ON DELETE CASCADE;

-- job_task.job_partition_id (original FK from V5)
ALTER TABLE batch.job_task
    DROP CONSTRAINT IF EXISTS job_task_job_partition_id_fkey,
    ADD CONSTRAINT job_task_job_partition_id_fkey
        FOREIGN KEY (job_partition_id) REFERENCES batch.job_partition(id) ON DELETE CASCADE;

-- job_step_instance.job_task_id (original FK from V13)
ALTER TABLE batch.job_step_instance
    DROP CONSTRAINT IF EXISTS job_step_instance_job_task_id_fkey,
    ADD CONSTRAINT job_step_instance_job_task_id_fkey
        FOREIGN KEY (job_task_id) REFERENCES batch.job_task(id) ON DELETE CASCADE;
