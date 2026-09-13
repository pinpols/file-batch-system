SELECT count(*)
FROM batch.job_definition
WHERE tenant_id IN ('p2fa', 'p2fb', 'p2fc')
  AND job_code = 'atomic_sql_demo'
  AND job_type = 'ATOMIC'
  AND worker_group = 'atomic'
  AND queue_code = 'atomic_queue'
  AND timeout_seconds = 300
  AND default_params = jsonb_build_object('taskType', 'sql', 'sql', 'SELECT 1')
  AND enabled = true;
