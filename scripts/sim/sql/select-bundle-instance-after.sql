SELECT i.id
FROM batch.trigger_request r
JOIN batch.job_instance i ON i.id = r.related_job_instance_id AND i.tenant_id = r.tenant_id
WHERE r.tenant_id = :'tenant_id' AND r.request_id = :'request_id'
  AND i.job_code = :'job_code' AND i.id > :'after_id'::bigint
ORDER BY i.id DESC LIMIT 1;
