-- JobInstance 只能引用同一租户拥有的 TriggerRequest。
-- V247 已引入被引用的复合键。全新数据库契约保证
-- 安装约束时，该分区引用表为空。

ALTER TABLE batch.job_instance
    DROP CONSTRAINT IF EXISTS job_instance_p_trigger_request_id_fkey,
    -- squawk-ignore constraint-missing-not-valid
    ADD CONSTRAINT fk_job_instance_trigger_request_tenant
        FOREIGN KEY (tenant_id, trigger_request_id)
        REFERENCES batch.trigger_request (tenant_id, id);

COMMENT ON CONSTRAINT fk_job_instance_trigger_request_tenant
    ON batch.job_instance IS
    'JobInstance 的 TriggerRequest 必须属于同一租户；手工创建的实例可不关联请求。';

-- 保留现有 request id 查询前缀，并为外键使用的租户等值条件建立索引。

DROP INDEX IF EXISTS batch.idx_job_instance_trigger_request_id;
CREATE INDEX idx_job_instance_trigger_request_id
    ON batch.job_instance (trigger_request_id, tenant_id)
    WHERE trigger_request_id IS NOT NULL;
