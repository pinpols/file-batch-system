SELECT COUNT(*)
FROM :"schema".job_task
WHERE task_status = :'task_status'
  AND (:'tenant' = '' OR tenant_id = :'tenant');
