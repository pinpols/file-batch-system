-- =========================================================
-- V176 - Alert escalation ladder (ops alert follow-through)
-- Notes:
-- 1) Track how many escalation tiers an OPEN alert has climbed when it
--    stays unacknowledged past its ack-SLA, so ops sweeps can progressively
--    raise visibility instead of letting a stuck alert sit silently.
-- 2) Additive only: existing rows default to tier 0 (never escalated).
-- 3) alert_event has no archive.* mirror, so no archive migration is paired
--    (verified: no batch.alert_event_archive table exists).
-- =========================================================

ALTER TABLE batch.alert_event
    ADD COLUMN IF NOT EXISTS escalation_tier INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS escalated_at    TIMESTAMPTZ;

-- Sweep predicate: status='OPEN' AND escalation_tier < max AND last_seen_at old.
-- A partial index on OPEN rows keeps the recurring escalation scan cheap as the
-- table grows (most rows eventually move to ACKED/CLOSED and drop out).
CREATE INDEX IF NOT EXISTS idx_alert_event_escalation_scan
    ON batch.alert_event (last_seen_at)
    WHERE status = 'OPEN';
