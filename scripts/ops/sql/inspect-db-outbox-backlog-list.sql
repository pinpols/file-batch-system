SELECT event_type, COUNT(*) AS cnt, MIN(created_at) AS oldest
FROM :"schema".outbox_event
WHERE publish_status IN ('NEW','FAILED','PUBLISHING')
  AND created_at < NOW() - (:outbox_lag_seconds::bigint * INTERVAL '1 second')
GROUP BY event_type
ORDER BY oldest ASC
LIMIT 10;
