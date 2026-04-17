-- Add forward retry support for trigger_request (issue 5.7).
-- FORWARD_FAILED : orchestrator HTTP call failed; eligible for retry.
-- GIVE_UP        : max retries exhausted; manual intervention needed.
ALTER TABLE batch.trigger_request DROP CONSTRAINT IF EXISTS ck_trigger_request_status;
ALTER TABLE batch.trigger_request ADD CONSTRAINT ck_trigger_request_status
    CHECK (request_status IN ('PENDING', 'PROCESSING', 'ACCEPTED', 'DUPLICATE', 'REJECTED', 'LAUNCHED', 'FORWARD_FAILED', 'GIVE_UP'));

ALTER TABLE batch.trigger_request ADD COLUMN IF NOT EXISTS forward_retry_count INTEGER NOT NULL DEFAULT 0;
