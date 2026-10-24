-- Workflow is a top-level definition and run; it is no longer a Job execution type.
-- The retired value is replaced by the narrower CHECK below in this transactional migration.
-- squawk-ignore ban-drop-constraint
ALTER TABLE batch.job_definition DROP CONSTRAINT IF EXISTS ck_job_definition_job_type;
ALTER TABLE batch.job_definition
    ADD CONSTRAINT ck_job_definition_job_type
        CHECK (job_type IN ('GENERAL', 'IMPORT', 'EXPORT', 'PROCESS', 'DISPATCH',
                            'ATOMIC', 'BUNDLE_IMPORT', 'BUNDLE_EXPORT', 'BUNDLE_DISPATCH')) NOT VALID;
ALTER TABLE batch.job_definition VALIDATE CONSTRAINT ck_job_definition_job_type;

COMMENT ON CONSTRAINT ck_job_definition_job_type ON batch.job_definition IS
    'Only executable Job types are allowed; Workflow definitions are independent top-level execution objects.';
