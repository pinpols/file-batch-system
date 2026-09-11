SELECT worker_code,
       worker_group,
       capability_tags,
       coalesce(status::text, '?') AS status
FROM batch.worker_registry
WHERE tenant_id = :'tenant_id'
ORDER BY id DESC
LIMIT 10;
