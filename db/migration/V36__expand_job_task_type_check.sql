-- Align job_task.task_type CHECK with worker route types used at dispatch (IMPORT / EXPORT / DISPATCH).
ALTER TABLE batch.job_task DROP CONSTRAINT IF EXISTS ck_job_task_type;
ALTER TABLE batch.job_task ADD CONSTRAINT ck_job_task_type CHECK (task_type IN (
    'EXECUTION',
    'COMPENSATION',
    'REPLAY',
    'IMPORT',
    'EXPORT',
    'DISPATCH',
    'GENERAL',
    'WORKFLOW'
));
