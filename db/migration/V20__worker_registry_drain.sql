-- =========================================================
-- V19 - Worker graceful drain fields
-- Notes:
-- 1) Record drain start and takeover deadline on worker_registry.
-- 2) Let orchestrator reclaim in-flight work after the deadline passes.
-- =========================================================

ALTER TABLE batch.worker_registry
    ADD COLUMN IF NOT EXISTS drain_started_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS drain_deadline_at TIMESTAMPTZ;

COMMENT ON COLUMN batch.worker_registry.drain_started_at IS 'When DRAINING was requested';
COMMENT ON COLUMN batch.worker_registry.drain_deadline_at IS 'After this instant, orchestrator may takeover in-flight tasks';
