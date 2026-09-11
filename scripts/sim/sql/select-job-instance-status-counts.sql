SELECT instance_status, count(*)
FROM batch.job_instance
WHERE tenant_id = :'tenant_id'
  AND job_code = :'job_code'
  AND created_at >= :'start_ts'::timestamptz
GROUP BY instance_status
ORDER BY instance_status;
