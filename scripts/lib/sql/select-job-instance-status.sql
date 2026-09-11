SELECT instance_status
FROM batch.job_instance
WHERE tenant_id = :'tenant_id'
  AND id = :'instance_id'::bigint;
