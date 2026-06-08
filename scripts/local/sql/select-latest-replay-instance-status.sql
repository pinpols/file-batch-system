SELECT instance_status
FROM batch.job_instance
WHERE tenant_id = :'tenant_id'
  AND job_code = :'job_code'
  AND biz_date = :'biz_date'::date
ORDER BY id DESC
LIMIT 1;
