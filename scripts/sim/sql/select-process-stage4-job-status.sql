SELECT instance.id,
       instance.job_code,
       instance.instance_status,
       task.task_status,
       task.error_code,
       left(coalesce(task.error_message, ''), 180) AS error_message
FROM batch.job_instance instance
LEFT JOIN batch.job_task task
  ON task.tenant_id = instance.tenant_id
 AND task.job_instance_id = instance.id
WHERE instance.tenant_id = :'tenant_id'
  AND instance.job_code = ANY(string_to_array(:'job_codes', ','))
  AND instance.created_at >= :'start_ts'::timestamptz
ORDER BY instance.created_at, instance.id;
