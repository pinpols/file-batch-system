SELECT instance.id || '|' || task.id || '|' || task.task_status
FROM batch.trigger_request request
JOIN batch.job_instance instance ON instance.id = request.related_job_instance_id
JOIN batch.job_task task ON task.job_instance_id = instance.id
WHERE request.tenant_id = :'tenant_id'
  AND request.request_id = :'request_id'
ORDER BY task.id DESC
LIMIT 1;
