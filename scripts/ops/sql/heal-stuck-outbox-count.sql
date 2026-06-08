SELECT COUNT(*)
FROM :"schema".outbox_event
WHERE publish_status = 'PUBLISHING'
  AND updated_at < NOW() - (:stuck_seconds::bigint * INTERVAL '1 second');
