SELECT COUNT(*)
FROM :"schema".job_partition
WHERE partition_status = :'partition_status'
  AND (:'tenant' = '' OR tenant_id = :'tenant');
