-- PROCESS staging keeps a stable batch_key for legal checkpoint resume. A fresh generation is
-- assigned whenever COMPUTE actually executes, so a recomputation cannot mix old staging rows.
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
    'Current PROCESS staging generation; rotated only when COMPUTE executes and retained across checkpoint resume.';
COMMENT ON COLUMN batch.job_task.process_staging_batch_key IS
    'Exact PROCESS batch_key paired with process_staging_generation_id for cleanup and audit.';
COMMENT ON COLUMN archive.job_task_archive.process_staging_generation_id IS
    'Last PROCESS staging generation at the time the task was archived.';
COMMENT ON COLUMN archive.job_task_archive.process_staging_batch_key IS
    'Exact PROCESS batch_key paired with the archived staging generation.';
