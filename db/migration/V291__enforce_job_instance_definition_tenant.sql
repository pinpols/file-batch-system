-- JobInstance 只能引用同租户的 JobDefinition。
-- V237 已为被引用的 (tenant_id, id) 提供唯一索引。
-- PostgreSQL 不支持在分区引用表上添加 NOT VALID 外键。
-- 全新数据库部署执行此迁移时，尚无 JobInstance 业务数据。
ALTER TABLE batch.job_instance
    DROP CONSTRAINT IF EXISTS job_instance_p_job_definition_id_fkey,
    -- squawk-ignore constraint-missing-not-valid
    ADD CONSTRAINT fk_job_instance_job_definition_tenant
        FOREIGN KEY (tenant_id, job_definition_id)
        REFERENCES batch.job_definition (tenant_id, id);
