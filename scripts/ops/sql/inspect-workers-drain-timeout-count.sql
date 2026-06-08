SELECT COUNT(*)
FROM :"schema".worker_registry
WHERE status = 'DRAINING'
  AND drain_deadline_at IS NOT NULL
  AND drain_deadline_at < NOW();
