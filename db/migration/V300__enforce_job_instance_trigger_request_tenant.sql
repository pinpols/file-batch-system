-- JobInstance may reference only a TriggerRequest owned by the same tenant.
-- The referenced composite key was introduced in V247. The fresh-database contract means
-- this partitioned referencing table is empty when the constraint is installed.
ALTER TABLE batch.job_instance
    DROP CONSTRAINT IF EXISTS job_instance_p_trigger_request_id_fkey,
    -- squawk-ignore constraint-missing-not-valid
    ADD CONSTRAINT fk_job_instance_trigger_request_tenant
        FOREIGN KEY (tenant_id, trigger_request_id)
        REFERENCES batch.trigger_request (tenant_id, id);

COMMENT ON CONSTRAINT fk_job_instance_trigger_request_tenant
    ON batch.job_instance IS
    'JobInstance trigger request must belong to the same tenant; manual instances may omit the request';

-- Keep the existing request-id lookup prefix while indexing the tenant equality used by the FK.
DROP INDEX IF EXISTS batch.idx_job_instance_trigger_request_id;
CREATE INDEX idx_job_instance_trigger_request_id
    ON batch.job_instance (trigger_request_id, tenant_id)
    WHERE trigger_request_id IS NOT NULL;
