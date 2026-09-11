SELECT coalesce(instance.instance_status, '')
FROM batch.trigger_request request
LEFT JOIN batch.job_instance instance ON instance.id = request.related_job_instance_id
WHERE request.tenant_id = :'tenant_id'
  AND request.request_id = :'request_id'
  AND request.job_code = :'job_code'
ORDER BY request.created_at DESC
LIMIT 1;
