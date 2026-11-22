-- 为 trigger_request 增加向前重试支持（issue 5.7）。
-- FORWARD_FAILED：Orchestrator HTTP 调用失败，允许重试。
-- GIVE_UP：重试次数耗尽，需要人工处理。

ALTER TABLE batch.trigger_request DROP CONSTRAINT IF EXISTS ck_trigger_request_status;
ALTER TABLE batch.trigger_request ADD CONSTRAINT ck_trigger_request_status
    CHECK (request_status IN ('PENDING', 'PROCESSING', 'ACCEPTED', 'DUPLICATE', 'REJECTED', 'LAUNCHED', 'FORWARD_FAILED', 'GIVE_UP'));

ALTER TABLE batch.trigger_request ADD COLUMN IF NOT EXISTS forward_retry_count INTEGER NOT NULL DEFAULT 0;
