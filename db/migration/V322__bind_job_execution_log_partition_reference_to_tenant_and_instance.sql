-- 关联分区的执行日志必须保留对应分区的租户和 JobInstance 标识，
-- 同时在删除分区时继续保留审计记录。

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM batch.job_execution_log log
          LEFT JOIN batch.job_partition partition
            ON partition.id = log.job_partition_id
         WHERE log.job_partition_id IS NOT NULL
           AND (log.job_instance_id IS NULL
                OR partition.id IS NULL
                OR partition.tenant_id IS DISTINCT FROM log.tenant_id
                OR partition.job_instance_id IS DISTINCT FROM log.job_instance_id)
    ) THEN
        RAISE EXCEPTION
            'job_execution_log contains a partition reference with a mismatched tenant or JobInstance';
    END IF;
END;
$$;

ALTER TABLE batch.job_execution_log
    DROP CONSTRAINT IF EXISTS job_execution_log_job_partition_id_fkey;

ALTER TABLE batch.job_execution_log
    ADD CONSTRAINT ck_job_execution_log_partition_requires_instance
        CHECK (job_partition_id IS NULL OR job_instance_id IS NOT NULL)
        NOT VALID;

ALTER TABLE batch.job_execution_log
    VALIDATE CONSTRAINT ck_job_execution_log_partition_requires_instance;

ALTER TABLE batch.job_execution_log
    ADD CONSTRAINT fk_job_execution_log_partition_tenant_instance
        FOREIGN KEY (tenant_id, job_partition_id, job_instance_id)
        REFERENCES batch.job_partition (tenant_id, id, job_instance_id)
        ON DELETE SET NULL (job_partition_id)
        NOT VALID;

ALTER TABLE batch.job_execution_log
    VALIDATE CONSTRAINT fk_job_execution_log_partition_tenant_instance;

COMMENT ON CONSTRAINT fk_job_execution_log_partition_tenant_instance
    ON batch.job_execution_log IS
    '关联分区的审计日志必须匹配分区所属租户和 JobInstance；删除分区时仅清除该引用。';
