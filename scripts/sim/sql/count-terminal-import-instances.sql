SELECT count(*)
FROM batch.job_instance
WHERE tenant_id = :'tenant_id'
  AND created_at >= :'start_ts'::timestamptz
  AND job_code IN ('TA_IMPORT_CUSTOMER_XML', 'TA_IMPORT_CUSTOMER_FIXED')
  AND instance_status IN ('SUCCESS', 'FAILED', 'PARTIAL_FAILED', 'REJECTED', 'CANCELLED');
