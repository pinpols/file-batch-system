SELECT count(*) FROM batch.trigger_request request
JOIN batch.job_instance instance
  ON instance.tenant_id = request.tenant_id AND instance.id = request.related_job_instance_id
WHERE request.tenant_id = :'tenant_id' AND request.request_id LIKE :'batch_prefix' || '-storm-%'
  AND instance.instance_status IN ('SUCCESS','FAILED','PARTIAL_FAILED','REJECTED','CANCELLED');
