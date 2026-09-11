SELECT max_concurrent
FROM batch.worker_registry
WHERE worker_code = :'worker_code'
  AND status = 'ONLINE'
ORDER BY heartbeat_at DESC
LIMIT 1;
