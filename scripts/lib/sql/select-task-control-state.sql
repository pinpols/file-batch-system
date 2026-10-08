SELECT task_status || '|' || cancel_requested::text
FROM batch.job_task
WHERE tenant_id = :'tenant_id'
  AND id = :'task_id'::bigint;
