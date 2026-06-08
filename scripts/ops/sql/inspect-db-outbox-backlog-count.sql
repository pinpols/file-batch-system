SELECT COUNT(*)
FROM :"schema".outbox_event
WHERE publish_status IN ('NEW','FAILED','PUBLISHING')
  AND created_at < NOW() - (:outbox_lag_seconds::bigint * INTERVAL '1 second');
