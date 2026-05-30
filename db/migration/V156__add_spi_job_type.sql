-- ADR-029:专用 SPI worker(batch-worker-spi)用 job_type='SPI' 派发原子任务(shell/sql/stored-proc/http)。
-- 放开 job_definition.job_type 与 job_task.task_type 的 CHECK 白名单,允许 'SPI'。
-- pipeline_type 不动(SPI 不是文件 pipeline)。

ALTER TABLE batch.job_definition
    DROP CONSTRAINT IF EXISTS ck_job_definition_job_type;

ALTER TABLE batch.job_definition
    ADD CONSTRAINT ck_job_definition_job_type
        CHECK (job_type IN ('GENERAL', 'IMPORT', 'EXPORT', 'PROCESS', 'DISPATCH', 'WORKFLOW', 'SPI'));

ALTER TABLE batch.job_task
    DROP CONSTRAINT IF EXISTS ck_job_task_type;

ALTER TABLE batch.job_task
    ADD CONSTRAINT ck_job_task_type
        CHECK (task_type IN ('EXECUTION', 'COMPENSATION', 'REPLAY', 'IMPORT', 'EXPORT', 'PROCESS', 'DISPATCH', 'GENERAL', 'WORKFLOW', 'SPI'));
