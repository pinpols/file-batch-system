SELECT count(*)
FROM batch.job_instance
WHERE tenant_id = :'tenant_id'
  AND job_code = :'job_code'
  AND created_at >= :'start_ts'::timestamptz
  AND instance_status IN ('SUCCESS', 'FAILED', 'PARTIAL_FAILED', 'REJECTED', 'CANCELLED');
