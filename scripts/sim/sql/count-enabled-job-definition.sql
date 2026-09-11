SELECT count(*)
FROM batch.job_definition
WHERE tenant_id = :'tenant_id'
  AND job_code = :'job_code'
  AND enabled = true;
