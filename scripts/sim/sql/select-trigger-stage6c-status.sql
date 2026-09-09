SELECT trigger_type, job_code, request_status, count(*)
FROM batch.trigger_request
WHERE tenant_id = :'tenant_id'
  AND (request_id LIKE :'batch_prefix' || '%' OR created_at >= :'start_ts'::timestamptz)
GROUP BY trigger_type, job_code, request_status
ORDER BY trigger_type, job_code, request_status;
