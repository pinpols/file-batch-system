-- 扩展 trigger_request.request_status CHECK，增加 PENDING 和 PROCESSING 状态。
-- PENDING：在转发给 Orchestrator 前写入，支持崩溃安全的两阶段写入。
-- PROCESSING：并发审批补跑期间使用的临时 CAS 状态。

ALTER TABLE batch.trigger_request DROP CONSTRAINT IF EXISTS ck_trigger_request_status;
ALTER TABLE batch.trigger_request ADD CONSTRAINT ck_trigger_request_status
    CHECK (request_status IN ('PENDING', 'PROCESSING', 'ACCEPTED', 'DUPLICATE', 'REJECTED', 'LAUNCHED'));
