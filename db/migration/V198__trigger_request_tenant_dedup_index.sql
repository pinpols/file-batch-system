-- flyway:executeInTransaction=false
-- Trigger API 每次接收 launch 都先按 (tenant_id, dedup_key) 查询既有请求。
-- V37 有意取消该列集的唯一约束（不同 request_id 可以复用业务 dedup key），但不应同时失去查询索引。
-- 该表是持续写入热表，必须并发建索引，避免生产迁移阻塞 API 请求与 Quartz fire。
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_trigger_request_tenant_dedup
    ON batch.trigger_request (tenant_id, dedup_key);
