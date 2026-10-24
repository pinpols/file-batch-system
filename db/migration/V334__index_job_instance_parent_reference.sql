-- Archive integrity diagnostics probe parent_instance_id across monthly partitions.
-- Index only real parent links so ordinary root executions do not pay write/storage cost.
CREATE INDEX IF NOT EXISTS idx_job_instance_p_parent_instance_tenant
    ON batch.job_instance (parent_instance_id, tenant_id)
    WHERE parent_instance_id IS NOT NULL;
