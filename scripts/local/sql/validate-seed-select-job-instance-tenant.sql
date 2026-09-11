SELECT tenant_id
FROM batch.job_instance
WHERE id = :'instance_id'::bigint;
