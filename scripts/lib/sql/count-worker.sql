SELECT count(*)
FROM batch.worker_registry
WHERE tenant_id = :'tenant_id'
  AND worker_code = :'worker_code';
