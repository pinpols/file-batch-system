-- Flyway 配置：executeInTransaction=false

-- 全局归档候选扫描会核对子记录归属是否与父 Outbox 记录一致。
-- 为两个反连接都建立索引，避免完整性检查扫描全部投递和重试历史。

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_event_delivery_log_outbox_tenant
    ON batch.event_delivery_log (outbox_event_id, tenant_id);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_event_outbox_retry_outbox_tenant
    ON batch.event_outbox_retry (outbox_event_id, tenant_id);
