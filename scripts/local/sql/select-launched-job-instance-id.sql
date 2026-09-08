SELECT related_job_instance_id
FROM batch.trigger_request
WHERE tenant_id = :'tenant_id'
  AND request_id = :'request_id'
  AND request_status = 'LAUNCHED';
