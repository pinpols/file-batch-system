-- job_partition deletion executes the job_step_instance FK cascade/check.  The
-- task lookup index does not cover this direct partition reference, so retention
-- and test-fixture cleanup otherwise degrade to repeated table scans.
CREATE INDEX IF NOT EXISTS idx_job_step_instance_partition_id
    ON batch.job_step_instance (job_partition_id)
    WHERE job_partition_id IS NOT NULL;
