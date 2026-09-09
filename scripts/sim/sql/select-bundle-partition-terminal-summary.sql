SELECT count(*) FILTER (WHERE partition_status IN ('SUCCESS', 'SUCCEEDED')),
       count(*) FILTER (WHERE partition_status LIKE '%FAIL%'), count(*)
FROM batch.job_partition
WHERE tenant_id = :'tenant_id' AND job_instance_id = :'instance_id'::bigint;
