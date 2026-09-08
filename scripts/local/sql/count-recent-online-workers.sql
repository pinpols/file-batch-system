SELECT count(*)
FROM batch.worker_registry
WHERE worker_group = :'worker_group'
  AND status = 'ONLINE'
  AND heartbeat_at >= CURRENT_TIMESTAMP - make_interval(secs => :'heartbeat_window_seconds'::integer);
