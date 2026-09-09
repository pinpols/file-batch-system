SELECT count(*)
FROM batch.job_instance
WHERE tenant_id = :'tenant_id'
  AND job_code = ANY(string_to_array(:'job_codes', ','))
  AND created_at >= :'start_ts'::timestamptz
  AND instance_status IN ('SUCCESS', 'FAILED', 'PARTIAL_FAILED', 'REJECTED', 'CANCELLED');
