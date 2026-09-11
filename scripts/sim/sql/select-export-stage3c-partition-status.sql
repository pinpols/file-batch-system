SELECT partition.partition_no, partition.partition_status, task.task_status, task.error_code
FROM batch.job_partition partition
LEFT JOIN batch.job_task task ON task.tenant_id = partition.tenant_id AND task.job_partition_id = partition.id
WHERE partition.tenant_id = :'tenant_id' AND partition.job_instance_id = :'instance_id'::bigint
ORDER BY partition.partition_no;
