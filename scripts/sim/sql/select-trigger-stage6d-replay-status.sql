SELECT request_status || '|' || coalesce(related_job_instance_id::text, '')
FROM batch.trigger_request WHERE tenant_id = :'tenant_id' AND id = :'request_id'::bigint;
