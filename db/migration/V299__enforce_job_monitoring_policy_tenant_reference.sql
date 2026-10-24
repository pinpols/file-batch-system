-- Keep the monitoring policy tenant aligned with its Job definition.
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
    'Tenant-scoped owner Job definition; policy remains dependent and is removed with its own parent';
