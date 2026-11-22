-- PROCESS staging 为合法的检查点续跑保留稳定的 batch_key。每次实际执行 COMPUTE 时
-- 都会分配新一代标识，避免重新计算混用旧批次的 staging 行。

ALTER TABLE batch.job_task
    ADD COLUMN process_staging_generation_id UUID,
    ADD COLUMN process_staging_batch_key TEXT,
    ADD CONSTRAINT ck_job_task_process_staging_generation_pair
        CHECK ((process_staging_generation_id IS NULL) = (process_staging_batch_key IS NULL)) NOT VALID;

ALTER TABLE batch.job_task
    VALIDATE CONSTRAINT ck_job_task_process_staging_generation_pair;

ALTER TABLE archive.job_task_archive
    ADD COLUMN process_staging_generation_id UUID,
    ADD COLUMN process_staging_batch_key TEXT,
    ADD CONSTRAINT ck_job_task_archive_process_staging_generation_pair
        CHECK ((process_staging_generation_id IS NULL) = (process_staging_batch_key IS NULL)) NOT VALID;

ALTER TABLE archive.job_task_archive
    VALIDATE CONSTRAINT ck_job_task_archive_process_staging_generation_pair;

COMMENT ON COLUMN batch.job_task.process_staging_generation_id IS
    '当前 PROCESS 暂存代次；仅在执行 COMPUTE 时轮换，检查点恢复时保持不变。';
COMMENT ON COLUMN batch.job_task.process_staging_batch_key IS
    '与 process_staging_generation_id 配对的精确 PROCESS batch_key，用于清理和审计。';
COMMENT ON COLUMN archive.job_task_archive.process_staging_generation_id IS
    '任务归档时最后一次 PROCESS 暂存代次。';
COMMENT ON COLUMN archive.job_task_archive.process_staging_batch_key IS
    '与归档暂存代次配对的精确 PROCESS batch_key。';
