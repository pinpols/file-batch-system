SELECT request.request_id,
       instance.id,
       instance.instance_status,
       task.task_status,
       task.error_code
FROM batch.trigger_request request
JOIN batch.job_instance instance ON instance.id = request.related_job_instance_id
LEFT JOIN batch.job_task task ON task.job_instance_id = instance.id
WHERE request.tenant_id = :'tenant_id'
  AND request.request_id = ANY(string_to_array(:'request_ids', ','))
ORDER BY request.request_id, task.id;
