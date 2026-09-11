SELECT instance_no,
       job_code,
       instance_status,
       worker_group
FROM batch.job_instance
WHERE tenant_id = :'tenant_id'
ORDER BY id DESC
LIMIT 5;
