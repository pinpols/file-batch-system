-- V324: Keep a hot file-bundle partition bound to its same-tenant source file.
-- The cold archive intentionally retains source_file_id as historical evidence and has no hot FK.
-- This branch deploys to a fresh database; install and validate the composite reference at bootstrap.

CREATE UNIQUE INDEX uk_file_record_tenant_id
    ON batch.file_record (tenant_id, id);

CREATE INDEX idx_job_partition_source_file_tenant
    ON batch.job_partition (tenant_id, source_file_id);

ALTER TABLE batch.job_partition
    ADD CONSTRAINT fk_job_partition_source_file_tenant
    FOREIGN KEY (tenant_id, source_file_id)
    REFERENCES batch.file_record (tenant_id, id)
    ON DELETE NO ACTION
    NOT VALID;

ALTER TABLE batch.job_partition
    VALIDATE CONSTRAINT fk_job_partition_source_file_tenant;

COMMENT ON CONSTRAINT fk_job_partition_source_file_tenant ON batch.job_partition IS
    'Binds a hot file-bundle partition to an existing source file owned by the same tenant; the cold archive keeps a historical weak reference.';
