ALTER TABLE batch.worker_registry
    ADD COLUMN IF NOT EXISTS drain_started_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS drain_deadline_at TIMESTAMPTZ;

COMMENT ON COLUMN batch.worker_registry.drain_started_at IS 'When DRAINING was requested';
COMMENT ON COLUMN batch.worker_registry.drain_deadline_at IS 'After this instant, orchestrator may takeover in-flight tasks';
