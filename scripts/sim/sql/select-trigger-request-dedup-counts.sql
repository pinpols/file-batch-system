SELECT count(*) AS trigger_rows,
       count(DISTINCT related_job_instance_id) AS instances
FROM batch.trigger_request
WHERE tenant_id = :'tenant_id'
  AND request_id = :'request_id';
