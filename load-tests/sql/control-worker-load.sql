SELECT
    worker_group,
    worker_code,
    status,
    current_load,
    max_concurrent,
    round(extract(epoch FROM clock_timestamp() - heartbeat_at)::numeric, 1) AS heartbeat_age_s
FROM batch.worker_registry
WHERE tenant_id = :'tenant_id'
  AND worker_group IN ('PROCESS','DISPATCH','ATOMIC')
ORDER BY worker_group, worker_code;
