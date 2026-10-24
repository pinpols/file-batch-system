-- 为 misfire 审批按租户和 catch-up 请求定位原始待处理记录提供索引。

CREATE INDEX idx_trigger_misfire_pending_catch_up_request
    ON batch.trigger_misfire_pending (tenant_id, catch_up_request_id)
    WHERE catch_up_request_id IS NOT NULL;

COMMENT ON INDEX batch.idx_trigger_misfire_pending_catch_up_request IS
    'Console 审批前按租户和请求 ID 定位原 misfire 义务，再发布恢复 Outbox。';
