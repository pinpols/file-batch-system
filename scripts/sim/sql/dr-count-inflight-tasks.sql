SELECT count(*)
FROM batch.job_task
WHERE task_status IN ('RUNNING', 'READY');
