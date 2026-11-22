-- 归档完整性诊断需要跨月分区查询 parent_instance_id。
-- 仅为实际存在的父子关联建立索引，避免普通根运行承担额外写入和存储开销。

CREATE INDEX IF NOT EXISTS idx_job_instance_p_parent_instance_tenant
    ON batch.job_instance (parent_instance_id, tenant_id)
    WHERE parent_instance_id IS NOT NULL;
