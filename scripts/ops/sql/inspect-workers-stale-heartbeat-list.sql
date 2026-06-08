SELECT worker_code, tenant_id, worker_group, heartbeat_at
FROM :"schema".worker_registry
WHERE status = 'ONLINE'
  AND (
    heartbeat_at IS NULL
    OR heartbeat_at < NOW() - (:stale_heartbeat_minutes::bigint * INTERVAL '1 minute')
  )
ORDER BY heartbeat_at ASC NULLS FIRST
LIMIT 10;
