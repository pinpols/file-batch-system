SELECT count(DISTINCT related_job_instance_id) FROM batch.trigger_request
WHERE tenant_id = :'tenant_id' AND request_id LIKE :'request_prefix' || '%';
