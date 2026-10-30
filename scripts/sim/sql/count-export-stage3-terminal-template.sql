SELECT count(*)
FROM batch.job_instance
WHERE tenant_id = :'tenant_id'
  AND job_code = 'TA_EXPORT_REPORT'
  AND created_at >= :'start_ts'::timestamptz
  AND params_snapshot#>>'{effectiveParams,templateCode}' = :'template_code'
  AND instance_status IN ('SUCCESS', 'FAILED', 'PARTIAL_FAILED', 'REJECTED', 'CANCELLED');
