-- 为 Trigger Outbox 待发布事件扫描补充按计划发布时间和创建时间排序的索引。

CREATE INDEX IF NOT EXISTS idx_trigger_outbox_event_pending_order
    ON batch.trigger_outbox_event (next_publish_at, created_at, id)
    WHERE publish_status IN ('NEW', 'FAILED');
