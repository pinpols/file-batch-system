-- Workflow 是顶层定义和运行，不再属于 Job 执行类型。
-- 移除 Workflow 作为 Job 执行类型后，更新现有允许值列表。

ALTER TABLE batch.job_definition DROP CONSTRAINT IF EXISTS ck_job_definition_job_type;
ALTER TABLE batch.job_definition
    ADD CONSTRAINT ck_job_definition_job_type
        CHECK (job_type IN ('GENERAL', 'IMPORT', 'EXPORT', 'PROCESS', 'DISPATCH',
                            'ATOMIC', 'BUNDLE_IMPORT', 'BUNDLE_EXPORT', 'BUNDLE_DISPATCH')) NOT VALID;
ALTER TABLE batch.job_definition VALIDATE CONSTRAINT ck_job_definition_job_type;

COMMENT ON CONSTRAINT ck_job_definition_job_type ON batch.job_definition IS
    '仅允许可执行的 Job 类型；Workflow 定义作为独立的顶层执行对象。';
