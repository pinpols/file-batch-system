-- Expand trigger_request.request_status CHECK to include PENDING and PROCESSING.
-- PENDING  : inserted before forwarding to orchestrator (crash-safe two-phase write).
-- PROCESSING : transient CAS state during concurrent catch-up approval.
ALTER TABLE batch.trigger_request DROP CONSTRAINT IF EXISTS ck_trigger_request_status;
ALTER TABLE batch.trigger_request ADD CONSTRAINT ck_trigger_request_status
    CHECK (request_status IN ('PENDING', 'PROCESSING', 'ACCEPTED', 'DUPLICATE', 'REJECTED', 'LAUNCHED'));
