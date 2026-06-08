CREATE INDEX IF NOT EXISTS idx_trigger_outbox_event_pending_order
    ON batch.trigger_outbox_event (next_publish_at, created_at, id)
    WHERE publish_status IN ('NEW', 'FAILED');
