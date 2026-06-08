UPDATE :"schema".outbox_event
SET publish_status = 'FAILED',
    next_publish_at = NOW(),
    updated_at = NOW()
WHERE publish_status = 'PUBLISHING'
  AND updated_at < NOW() - (:stuck_seconds::bigint * INTERVAL '1 second')
RETURNING id;
