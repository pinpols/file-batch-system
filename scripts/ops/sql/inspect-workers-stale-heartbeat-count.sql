SELECT COUNT(*)
FROM :"schema".worker_registry
WHERE status = 'ONLINE'
  AND (
    heartbeat_at IS NULL
    OR heartbeat_at < NOW() - (:stale_heartbeat_minutes::bigint * INTERVAL '1 minute')
  );
