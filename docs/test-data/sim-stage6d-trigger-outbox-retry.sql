-- Stage 6d 注入 trigger outbox 重试。
-- 需要的 psql 变量:request_prefix

UPDATE batch.trigger_outbox_event
SET publish_status = 'FAILED',
    publish_attempt = greatest(publish_attempt, 1),
    last_error = 'sim-stage6d forced retry',
    next_publish_at = CURRENT_TIMESTAMP,
    published_at = null,
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'ta'
  AND request_id LIKE :'request_prefix' || '%';
