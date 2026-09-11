SELECT count(*)
FROM batch.job_partition
WHERE job_instance_id = :'job_instance_id'::bigint
  AND partition_status NOT IN ('SUCCESS', 'FAILED', 'SKIPPED', 'CANCELLED');
