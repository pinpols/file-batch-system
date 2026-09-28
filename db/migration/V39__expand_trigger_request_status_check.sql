-- 扩展 trigger_request.request_status CHECK，增加 PENDING 和 PROCESSING 状态。
-- PENDING  : inserted before forwarding to orchestrator (crash-safe two-phase write).
-- PROCESSING : transient CAS state during concurrent catch-up approval.
ALTER TABLE batch.trigger_request DROP CONSTRAINT IF EXISTS ck_trigger_request_status;
ALTER TABLE batch.trigger_request ADD CONSTRAINT ck_trigger_request_status
    CHECK (request_status IN ('PENDING', 'PROCESSING', 'ACCEPTED', 'DUPLICATE', 'REJECTED', 'LAUNCHED'));
