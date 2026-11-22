-- archive.job_task_archive 在 V71 中通过 INCLUDING CONSTRAINTS 从 job_task 创建。
-- 后续 Task/Pipeline 类型扩展只更新了热表 CHECK 约束，因此 PROCESS
-- 虽然能成功执行，其 Task/Pipeline 记录却无法归档。
-- 让每个冷表镜像接受的值与当前热表契约保持一致。

ALTER TABLE archive.job_task_archive
    DROP CONSTRAINT IF EXISTS ck_job_task_type;

ALTER TABLE archive.job_task_archive
    ADD CONSTRAINT ck_job_task_type
        CHECK (task_type IN (
            'EXECUTION', 'COMPENSATION', 'REPLAY', 'IMPORT', 'EXPORT', 'PROCESS',
            'DISPATCH', 'GENERAL', 'WORKFLOW', 'ATOMIC'
        )) NOT VALID;

ALTER TABLE archive.job_task_archive
    VALIDATE CONSTRAINT ck_job_task_type;

ALTER TABLE archive.pipeline_instance_archive
    DROP CONSTRAINT IF EXISTS ck_pipeline_instance_type;

ALTER TABLE archive.pipeline_instance_archive
    ADD CONSTRAINT ck_pipeline_instance_type
        CHECK (pipeline_type IN ('IMPORT', 'EXPORT', 'PROCESS', 'DISPATCH')) NOT VALID;

ALTER TABLE archive.pipeline_instance_archive
    VALIDATE CONSTRAINT ck_pipeline_instance_type;
