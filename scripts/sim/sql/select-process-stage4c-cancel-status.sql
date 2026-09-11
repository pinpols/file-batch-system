SELECT instance.id,
       instance.instance_status,
       partition.partition_status,
       task.task_status,
       task.cancel_requested,
       task.error_code
FROM batch.job_instance instance
JOIN batch.job_partition partition
  ON partition.tenant_id = instance.tenant_id
 AND partition.job_instance_id = instance.id
JOIN batch.job_task task
  ON task.tenant_id = partition.tenant_id
 AND task.job_partition_id = partition.id
WHERE instance.tenant_id = :'tenant_id'
  AND instance.id = :'instance_id'::bigint;
