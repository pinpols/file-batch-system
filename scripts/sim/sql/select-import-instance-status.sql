SELECT instance.id,
       instance.job_code,
       instance.instance_status,
       task.task_status,
       task.error_code,
       left(coalesce(task.error_message, ''), 160) AS error_message
FROM batch.job_instance instance
LEFT JOIN batch.job_task task ON task.job_instance_id = instance.id
WHERE instance.tenant_id = :'tenant_id'
  AND instance.created_at >= :'start_ts'::timestamptz
  AND instance.job_code IN ('TA_IMPORT_CUSTOMER_XML', 'TA_IMPORT_CUSTOMER_FIXED')
ORDER BY instance.created_at, instance.id;
