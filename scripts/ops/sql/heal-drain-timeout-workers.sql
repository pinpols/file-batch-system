SELECT worker_code || '|' || tenant_id || '|' || drain_deadline_at
FROM :"schema".worker_registry
WHERE status = 'DRAINING'
  AND drain_deadline_at IS NOT NULL
  AND drain_deadline_at < NOW()
ORDER BY drain_deadline_at ASC;
