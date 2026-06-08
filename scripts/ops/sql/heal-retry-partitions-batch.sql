SELECT id, tenant_id
FROM :"schema".job_partition
WHERE partition_status = :'partition_status'
  AND (:'tenant' = '' OR tenant_id = :'tenant')
ORDER BY id ASC
LIMIT :batch_size OFFSET :batch_offset;
