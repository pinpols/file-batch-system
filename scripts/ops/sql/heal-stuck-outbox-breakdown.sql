SELECT event_type, COUNT(*) AS cnt,
       MIN(updated_at) AS oldest, MAX(publish_attempt) AS max_attempts
FROM :"schema".outbox_event
WHERE publish_status = 'PUBLISHING'
  AND updated_at < NOW() - (:stuck_seconds::bigint * INTERVAL '1 second')
GROUP BY event_type
ORDER BY cnt DESC;
