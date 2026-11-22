-- flyway:executeInTransaction=false
-- IDX-02：旧式 Webhook 重投扫描领取 PENDING 与 EXHAUSTED，状态列表以绑定参数传入。
-- 原 idx_webhook_delivery_log_retry_eligible 只含 EXHAUSTED，PENDING 重投与服务端预编译的 generic plan 都无法使用。
-- 成功、放弃与领取都会清空 next_retry_at，只有重投失败会重新写入，因此谓词不含状态即可只覆盖待重投行，
-- 并与 V328 的终态投递候选索引对称（terminal_event_key IS NOT NULL）。
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_webhook_delivery_log_legacy_retry
    ON batch.webhook_delivery_log (next_retry_at, id)
    WHERE terminal_event_key IS NULL AND next_retry_at IS NOT NULL;

-- 旧 EXHAUSTED 专用索引已被新索引覆盖，只删除这一个具名索引，不涉及表或数据（已登记于 high-risk-operations.md）。
DROP INDEX CONCURRENTLY IF EXISTS batch.idx_webhook_delivery_log_retry_eligible;
