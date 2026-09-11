SELECT coalesce(instance.instance_status, '') || '|' ||
       coalesce(task.task_status, '') || '|' ||
       coalesce(task.error_code, '')
FROM batch.trigger_request request
LEFT JOIN batch.job_instance instance
  ON instance.tenant_id = request.tenant_id
 AND instance.id = request.related_job_instance_id
LEFT JOIN batch.job_task task
  ON task.tenant_id = instance.tenant_id
 AND task.job_instance_id = instance.id
WHERE request.tenant_id = :'tenant_id'
  AND request.request_id = :'request_id'
ORDER BY request.created_at DESC, task.id DESC
LIMIT 1;
