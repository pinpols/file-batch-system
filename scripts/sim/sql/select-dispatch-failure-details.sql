SELECT instance.instance_status,
       partition.partition_status,
       task.task_status,
       task.error_code,
       dispatch.dispatch_status,
       dispatch.error_code AS dispatch_error
FROM batch.job_instance instance
LEFT JOIN batch.job_partition partition ON partition.job_instance_id = instance.id
LEFT JOIN batch.job_task task ON task.job_partition_id = partition.id
LEFT JOIN batch.file_dispatch_record dispatch
    ON dispatch.tenant_id = instance.tenant_id
    AND dispatch.file_id = :'file_id'::bigint
    AND dispatch.channel_code = :'channel_code'
WHERE instance.tenant_id = :'tenant_id'
  AND instance.id = :'instance_id'::bigint;
