SELECT request.request_id,
       instance.id,
       instance.instance_status,
       task.task_status,
       task.error_code,
       left(coalesce(task.error_message, ''), 160) AS error_message
FROM batch.trigger_request request
JOIN batch.job_instance instance
  ON instance.tenant_id = request.tenant_id
 AND instance.id = request.related_job_instance_id
LEFT JOIN batch.job_task task
  ON task.tenant_id = instance.tenant_id
 AND task.job_instance_id = instance.id
WHERE request.tenant_id = :'tenant_id'
  AND request.request_id = ANY(string_to_array(:'request_ids', ','))
ORDER BY request.created_at, task.id;
