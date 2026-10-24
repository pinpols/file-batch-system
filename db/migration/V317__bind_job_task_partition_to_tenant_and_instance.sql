-- 将可选的 JobTask 分区关联到同租户、同一 JobInstance。
-- V58 创建的单列外键只能证明分区 ID 存在。

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM batch.job_task task
          JOIN batch.job_partition partition
            ON partition.id = task.job_partition_id
         WHERE task.tenant_id IS DISTINCT FROM partition.tenant_id
            OR task.job_instance_id IS DISTINCT FROM partition.job_instance_id
    ) THEN
        RAISE EXCEPTION 'job_task contains a partition owned by another tenant or JobInstance';
    END IF;
END;
$$;

CREATE UNIQUE INDEX uk_job_partition_tenant_id_instance
    ON batch.job_partition (tenant_id, id, job_instance_id);

CREATE INDEX idx_job_task_partition_tenant_instance
    ON batch.job_task (tenant_id, job_partition_id, job_instance_id)
    WHERE job_partition_id IS NOT NULL;

ALTER TABLE batch.job_task
    DROP CONSTRAINT job_task_job_partition_id_fkey;

ALTER TABLE batch.job_task
    ADD CONSTRAINT fk_job_task_partition_tenant_instance
        FOREIGN KEY (tenant_id, job_partition_id, job_instance_id)
        REFERENCES batch.job_partition (tenant_id, id, job_instance_id)
        ON DELETE CASCADE
        NOT VALID;

ALTER TABLE batch.job_task
    VALIDATE CONSTRAINT fk_job_task_partition_tenant_instance;

COMMENT ON CONSTRAINT fk_job_task_partition_tenant_instance
    ON batch.job_task IS
    '已分配的分区必须属于同一租户和 JobInstance；不关联分区的任务仍然有效。';
