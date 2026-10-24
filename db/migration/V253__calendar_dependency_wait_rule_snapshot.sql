-- =====================================================================
-- V238: preserve dependency rules observed when a calendar date first waits.
-- Resolution continues to use current enabled configuration so operators can
-- repair a dependency definition and let the reconciler release the wait.
-- =====================================================================

ALTER TABLE batch.calendar_dependency_wait
    ADD COLUMN IF NOT EXISTS dependency_snapshot JSONB;

COMMENT ON COLUMN batch.calendar_dependency_wait.dependency_snapshot IS
    '首次等待时观察到的上游 calendar 与 rule 快照，仅用于审计；重检按当前启用配置裁决，允许修复配置后自动释放';
