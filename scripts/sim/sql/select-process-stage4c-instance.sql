SELECT instance.id || '|' || coalesce(instance.instance_status, '')
FROM batch.trigger_request request
JOIN batch.job_instance instance
  ON instance.tenant_id = request.tenant_id
 AND instance.id = request.related_job_instance_id
WHERE request.tenant_id = :'tenant_id'
  AND request.request_id = :'request_id'
ORDER BY request.created_at DESC
LIMIT 1;
