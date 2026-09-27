SELECT instance.id AS job_instance_id,
       instance.job_code,
       instance.instance_status,
       jp.id AS partition_id,
       jp.partition_status,
       task.id AS task_id,
       task.task_status,
       task.error_code,
       left(coalesce(task.error_message, ''), 1600) AS error_message
FROM batch.trigger_request request
JOIN batch.job_instance instance
  ON instance.id = request.related_job_instance_id
LEFT JOIN batch.job_task task
  ON task.job_instance_id = instance.id
LEFT JOIN batch.job_partition jp
  ON jp.id = task.job_partition_id
WHERE request.tenant_id = :'tenant_id'
  AND request.request_id = :'request_id'
  AND request.job_code = :'job_code'
ORDER BY jp.id, task.id;
