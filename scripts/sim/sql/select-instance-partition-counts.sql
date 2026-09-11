SELECT count(*) || '|' || count(*) FILTER (WHERE partition_status = 'SUCCESS')
FROM batch.job_partition
WHERE tenant_id = :'tenant_id'
  AND job_instance_id = :'instance_id'::bigint;
