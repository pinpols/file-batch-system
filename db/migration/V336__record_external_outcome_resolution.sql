-- Record the task attempt version for which an operator verified that an
-- uncertain external action was not executed. Audit evidence remains in
-- job_execution_log and is archived with the task history.
ALTER TABLE batch.job_task
    ADD COLUMN external_outcome_resolution_version BIGINT;

ALTER TABLE archive.job_task_archive
    ADD COLUMN external_outcome_resolution_version BIGINT;

COMMENT ON COLUMN batch.job_task.external_outcome_resolution_version IS
    'Task version whose EXTERNAL_OUTCOME_UNKNOWN was manually verified as not executed; one authorization per failed attempt.';

COMMENT ON COLUMN archive.job_task_archive.external_outcome_resolution_version IS
    'Archived task version whose EXTERNAL_OUTCOME_UNKNOWN was manually verified as not executed.';
