-- =========================================================
-- V19 - Worker graceful drain fields
-- 说明：
-- 1) 在 worker_registry 中记录排空开始时间和接管截止时间。
-- 2) 截止时间后允许 orchestrator 回收执行中的任务。
-- =========================================================

ALTER TABLE batch.worker_registry
    ADD COLUMN IF NOT EXISTS drain_started_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS drain_deadline_at TIMESTAMPTZ;

COMMENT ON COLUMN batch.worker_registry.drain_started_at IS 'When DRAINING was requested';
COMMENT ON COLUMN batch.worker_registry.drain_deadline_at IS 'After this instant, orchestrator may takeover in-flight tasks';
