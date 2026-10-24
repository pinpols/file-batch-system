ALTER TABLE batch.workflow_run
    ADD COLUMN IF NOT EXISTS single_job_definition_id BIGINT;

ALTER TABLE archive.workflow_run_archive
    ADD COLUMN IF NOT EXISTS single_job_definition_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'fk_workflow_run_single_job_definition'
           AND conrelid = 'batch.workflow_run'::regclass
    ) THEN
        ALTER TABLE batch.workflow_run
            ADD CONSTRAINT fk_workflow_run_single_job_definition
            FOREIGN KEY (tenant_id, single_job_definition_id)
            REFERENCES batch.job_definition (tenant_id, id);
    END IF;
END $$;

COMMENT ON COLUMN batch.workflow_run.single_job_definition_id IS
    'Launch-time snapshot of the managed single-Job Workflow relation; null for composed and legacy runs.';
