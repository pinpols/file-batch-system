SELECT string_agg(DISTINCT task_status, ',')
FROM batch.job_task
WHERE tenant_id = :'tenant_id'
  AND job_instance_id = :'instance_id'::bigint;
