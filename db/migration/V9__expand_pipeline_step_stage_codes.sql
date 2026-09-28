-- =========================================================
-- V8 - 扩展流水线步骤阶段编码
-- 说明：
-- 1) 使 stage_code 枚举与文件处理生命周期保持一致。
-- 2) 本次仅扩展 pipeline_step_definition 允许的阶段值。
-- =========================================================

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
