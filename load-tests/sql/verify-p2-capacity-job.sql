SELECT count(*)
FROM batch.job_definition
WHERE tenant_id = :'capacity_tenant_id'
  AND job_code = 'atomic_sql_demo'
  AND job_type = 'ATOMIC'
  AND worker_group = 'atomic'
  AND queue_code = 'atomic_queue'
  AND timeout_seconds = :'capacity_job_timeout_seconds'::integer
  AND default_params = jsonb_build_object('taskType', 'sql', 'sql', 'SELECT 1')
  AND enabled = true;
