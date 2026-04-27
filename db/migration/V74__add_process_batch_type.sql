-- =========================================================
-- V74 - Add PROCESS batch / pipeline type
-- =========================================================

ALTER TABLE batch.job_definition
    DROP CONSTRAINT IF EXISTS ck_job_definition_job_type;

ALTER TABLE batch.job_definition
    ADD CONSTRAINT ck_job_definition_job_type
        CHECK (job_type IN ('GENERAL', 'IMPORT', 'EXPORT', 'PROCESS', 'DISPATCH', 'WORKFLOW'));

ALTER TABLE batch.job_task
    DROP CONSTRAINT IF EXISTS ck_job_task_type;

ALTER TABLE batch.job_task
    ADD CONSTRAINT ck_job_task_type
        CHECK (task_type IN ('EXECUTION', 'COMPENSATION', 'REPLAY', 'IMPORT', 'EXPORT', 'PROCESS', 'DISPATCH', 'GENERAL', 'WORKFLOW'));

ALTER TABLE batch.pipeline_definition
    DROP CONSTRAINT IF EXISTS ck_pipeline_definition_type;

ALTER TABLE batch.pipeline_definition
    ADD CONSTRAINT ck_pipeline_definition_type
        CHECK (pipeline_type IN ('IMPORT', 'EXPORT', 'PROCESS', 'DISPATCH'));

ALTER TABLE batch.pipeline_instance
    DROP CONSTRAINT IF EXISTS ck_pipeline_instance_type;

ALTER TABLE batch.pipeline_instance
    ADD CONSTRAINT ck_pipeline_instance_type
        CHECK (pipeline_type IN ('IMPORT', 'EXPORT', 'PROCESS', 'DISPATCH'));

ALTER TABLE batch.pipeline_step_definition
    DROP CONSTRAINT IF EXISTS ck_pipeline_step_stage;

ALTER TABLE batch.pipeline_step_definition
    ADD CONSTRAINT ck_pipeline_step_stage
        CHECK (
            stage_code IN (
                'PREPARE',
                'RECEIVE',
                'PREPROCESS',
                'PARSE',
                'VALIDATE',
                'LOAD',
                'COMPUTE',
                'GENERATE',
                'STORE',
                'REGISTER',
                'TRANSFER',
                'DISPATCH',
                'ACK',
                'RETRY',
                'COMPENSATE',
                'COMMIT',
                'COMPLETE',
                'FEEDBACK'
            )
        );
