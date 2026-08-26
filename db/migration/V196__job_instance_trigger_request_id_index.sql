-- Trigger API cleanup and retention delete trigger_request rows after their
-- linked job instances.  PostgreSQL does not create an index for a referencing
-- FK automatically; without one each trigger_request delete scans job_instance.
CREATE INDEX IF NOT EXISTS idx_job_instance_trigger_request_id
    ON batch.job_instance (trigger_request_id)
    WHERE trigger_request_id IS NOT NULL;
