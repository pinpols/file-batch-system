SELECT request_status || '|' || coalesce(related_job_instance_id::text, '')
FROM batch.trigger_request WHERE tenant_id = :'tenant_id' AND request_id = :'request_id';
