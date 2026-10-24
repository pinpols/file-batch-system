SELECT task.error_message
FROM batch.job_task task
WHERE task.tenant_id = :'tenant_id'
  AND task.job_instance_id = :'instance_id'::bigint;
