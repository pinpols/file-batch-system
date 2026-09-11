SELECT count(*)
FROM batch.trigger_request
WHERE tenant_id = :'tenant_id'
  AND job_code = :'job_code'
  AND trigger_type = 'SCHEDULED';
