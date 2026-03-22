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
                'GENERATE',
                'STORE',
                'REGISTER',
                'TRANSFER',
                'DISPATCH',
                'ACK',
                'RETRY',
                'COMPENSATE',
                'COMPLETE',
                'FEEDBACK'
            )
        );
