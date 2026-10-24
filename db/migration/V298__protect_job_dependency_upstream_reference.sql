-- Keep the upstream dependency within the same tenant and prevent deleting a referenced job.
ALTER TABLE batch.job_dependency
    ADD CONSTRAINT fk_job_dependency_upstream
        FOREIGN KEY (tenant_id, upstream_job_code)
        REFERENCES batch.job_definition (tenant_id, job_code)
        ON DELETE NO ACTION NOT VALID;

ALTER TABLE batch.job_dependency
    VALIDATE CONSTRAINT fk_job_dependency_upstream;

COMMENT ON CONSTRAINT fk_job_dependency_upstream ON batch.job_dependency IS
    'Tenant-scoped upstream job definition; NO ACTION preserves active dependency edges until explicitly removed';
