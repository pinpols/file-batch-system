SELECT (
    SELECT count(*)
    FROM batch.job_definition
    WHERE tenant_id = :'tenant_id'
      AND job_code = :'job_code'
) + (
    SELECT count(*)
    FROM batch.resource_queue
    WHERE tenant_id = :'tenant_id'
      AND queue_code = :'queue_code'
);
