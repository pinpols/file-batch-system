SELECT count(*) FROM batch.trigger_request request
JOIN batch.job_instance instance ON instance.tenant_id = request.tenant_id AND instance.id = request.related_job_instance_id
WHERE request.request_id = ANY(string_to_array(:'request_ids', ',')) AND instance.instance_status = 'SUCCESS';
