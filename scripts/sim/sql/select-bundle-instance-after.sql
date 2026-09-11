SELECT id FROM batch.job_instance
WHERE tenant_id = :'tenant_id' AND job_code = :'job_code' AND id > :'after_id'::bigint
ORDER BY id DESC LIMIT 1;
