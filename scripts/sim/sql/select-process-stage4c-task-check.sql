SELECT count(*) FILTER (WHERE task.task_status = 'SUCCESS') || '|' ||
       count(*) FILTER (WHERE partition.partition_status = 'SUCCESS')
FROM batch.job_partition partition
JOIN batch.job_task task
  ON task.tenant_id = partition.tenant_id
 AND task.job_partition_id = partition.id
WHERE partition.tenant_id = :'tenant_id'
  AND partition.job_instance_id = :'instance_id'::bigint;
