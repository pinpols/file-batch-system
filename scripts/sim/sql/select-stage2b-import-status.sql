SELECT instance.id,
       instance.job_code,
       instance.instance_status,
       instance.expected_partition_count,
       task.task_status,
       task.error_code,
       left(coalesce(task.error_message, ''), 180) AS error_message
FROM batch.trigger_request request
JOIN batch.job_instance instance ON instance.id = request.related_job_instance_id
LEFT JOIN batch.job_task task ON task.job_instance_id = instance.id
WHERE request.tenant_id = :'tenant_id'
  AND request.request_id = ANY(string_to_array(:'request_ids', ','))
ORDER BY instance.created_at, instance.id, task.id;
