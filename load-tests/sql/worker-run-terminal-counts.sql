SELECT count(*) || '|' || count(*) FILTER (
    WHERE instance_status IN ('SUCCESS','FAILED','PARTIAL_FAILED','CANCELLED','TERMINATED')
)
FROM batch.job_instance
WHERE tenant_id = :'tenant_id' AND job_code = :'job_code'
  AND params_snapshot::text LIKE '%' || :'run_id' || '%';
