-- flyway:executeInTransaction=false
-- TriggerOutboxRelay 每个 poll 周期都会回收超时 PUBLISHING 事件并统计其数量。
-- 只索引短暂的 PUBLISHING 子集，避免历史 PUBLISHED 行参与高频扫描；并发创建避免升级时阻塞 relay 写入。
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_trigger_outbox_event_publishing_stale
    ON batch.trigger_outbox_event (updated_at)
    WHERE publish_status = 'PUBLISHING';
