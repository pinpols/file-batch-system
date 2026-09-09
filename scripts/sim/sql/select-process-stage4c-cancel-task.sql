SELECT instance.id || '|' || partition.id || '|' || coalesce(task.task_status, '')
FROM batch.trigger_request request
JOIN batch.job_instance instance
  ON instance.tenant_id = request.tenant_id
 AND instance.id = request.related_job_instance_id
JOIN batch.job_partition partition
  ON partition.tenant_id = instance.tenant_id
 AND partition.job_instance_id = instance.id
JOIN batch.job_task task
  ON task.tenant_id = partition.tenant_id
 AND task.job_partition_id = partition.id
WHERE request.tenant_id = :'tenant_id'
  AND request.request_id = :'request_id'
ORDER BY task.id DESC
LIMIT 1;
