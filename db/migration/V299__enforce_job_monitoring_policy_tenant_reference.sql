-- 使监控策略的租户与其 Job 定义保持一致。

ALTER TABLE batch.job_monitoring_policy
    DROP CONSTRAINT job_monitoring_policy_job_definition_id_fkey;

ALTER TABLE batch.job_monitoring_policy
    ADD CONSTRAINT fk_job_monitoring_policy_job_definition_tenant
        FOREIGN KEY (tenant_id, job_definition_id)
        REFERENCES batch.job_definition (tenant_id, id)
        ON DELETE CASCADE NOT VALID;

ALTER TABLE batch.job_monitoring_policy
    VALIDATE CONSTRAINT fk_job_monitoring_policy_job_definition_tenant;

COMMENT ON CONSTRAINT fk_job_monitoring_policy_job_definition_tenant
    ON batch.job_monitoring_policy IS
    '租户范围内的所属 Job 定义；监控策略依赖该定义，并随自身父对象删除。';
