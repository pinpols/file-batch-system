SELECT request.tenant_id, request.request_id, instance.id, instance.job_code, instance.instance_status, instance.expected_partition_count
FROM batch.trigger_request request
JOIN batch.job_instance instance ON instance.tenant_id = request.tenant_id AND instance.id = request.related_job_instance_id
WHERE request.request_id = ANY(string_to_array(:'request_ids', ','))
ORDER BY request.tenant_id, instance.id;
