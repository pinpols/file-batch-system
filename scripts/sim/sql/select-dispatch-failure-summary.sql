SELECT instance.instance_status || '|' || partition.partition_status || '|'
           || task.task_status || '|' || coalesce(dispatch.dispatch_status, '') || '|'
           || coalesce(task.error_code, '') || '|' || coalesce(dispatch.error_code, '') || '|'
           || (SELECT count(*)::text
               FROM batch.retry_schedule retry
               WHERE retry.tenant_id = instance.tenant_id
                 AND retry.related_type = 'JOB_PARTITION'
                 AND retry.related_id = partition.id)
FROM batch.job_instance instance
JOIN batch.job_partition partition ON partition.job_instance_id = instance.id
JOIN batch.job_task task ON task.job_partition_id = partition.id
LEFT JOIN batch.file_dispatch_record dispatch
    ON dispatch.tenant_id = instance.tenant_id
    AND dispatch.file_id = :'file_id'::bigint
    AND dispatch.channel_code = :'channel_code'
WHERE instance.tenant_id = :'tenant_id'
  AND instance.id = :'instance_id'::bigint;
