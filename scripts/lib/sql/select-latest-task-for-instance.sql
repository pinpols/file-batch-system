SELECT id
FROM batch.job_task
WHERE tenant_id = :'tenant_id'
  AND job_instance_id = :'instance_id'::bigint
ORDER BY id DESC
LIMIT 1;
