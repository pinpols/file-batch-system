-- 删除 job_partition 时会执行 job_step_instance 外键级联/检查。任务查询索引不覆盖此分区引用，
-- 因此保留策略和测试数据清理需要单独的索引支持。
CREATE INDEX IF NOT EXISTS idx_job_step_instance_partition_id
    ON batch.job_step_instance (job_partition_id)
    WHERE job_partition_id IS NOT NULL;
