-- =========================================================
-- V181 - Alert escalation notify watermark (close the last-mile loop)
-- Notes:
-- 1) V176 added escalation_tier/escalated_at so ops sweeps raise an OPEN alert's
--    visibility (log + metric) when it stays unacknowledged past ack-SLA. That
--    ladder is headless: nobody is actively notified. This column lets the
--    console-side notifier fire an in-platform webhook exactly once per tier
--    climb and remember how far it has already notified.
-- 2) escalation_notified_tier = highest escalation_tier already pushed to the
--    notification path. Notifier picks rows where escalation_tier >
--    escalation_notified_tier, dispatches, then CAS-bumps this watermark.
-- 3) Additive only: existing rows default to 0 (never notified) and will be
--    (re)notified on their next tier climb, not retroactively spammed, because
--    a tier-0 OPEN alert has escalation_tier=0 (0 > 0 is false).
-- 4) alert_event has no archive.* mirror, so no archive migration is paired
--    (verified: no batch.alert_event_archive table exists).
-- =========================================================

ALTER TABLE batch.alert_event
    ADD COLUMN IF NOT EXISTS escalation_notified_tier INTEGER NOT NULL DEFAULT 0;

-- Notify scan predicate: status='OPEN' AND escalation_tier > escalation_notified_tier.
-- Partial index on OPEN rows keeps the recurring notify scan cheap as the table
-- grows (most rows settle into ACKED/CLOSED and drop out of the partial index).
CREATE INDEX IF NOT EXISTS idx_alert_event_escalation_notify
    ON batch.alert_event (escalation_tier, escalation_notified_tier)
    WHERE status = 'OPEN';
