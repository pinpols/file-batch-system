-- =========================================================
-- V350：readiness 等待的截止时间落库。
--
-- 背景：等待截止时间此前只存在于 Quartz JobDataMap（readinessDeferredSince /
-- readinessOriginalFireTime）。Quartz 回调一旦丢失，等待既无法恢复也无法超窗终结。
-- 新增两列让等待记录自证 deadline，由周期扫尾器在无 Quartz 回调时把超窗等待
-- 终结为 READINESS_TIMEOUT 并结束触发侧义务。
-- =========================================================

ALTER TABLE batch.scheduled_occurrence
    ADD COLUMN IF NOT EXISTS readiness_deferred_since TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS readiness_deadline TIMESTAMPTZ;

-- 扫尾器只关心 WAITING_READINESS 且已到期的记录；部分索引避免全表扫描。
CREATE INDEX IF NOT EXISTS idx_scheduled_occurrence_readiness_deadline
    ON batch.scheduled_occurrence (readiness_deadline)
    WHERE occurrence_status = 'WAITING_READINESS';

COMMENT ON COLUMN batch.scheduled_occurrence.readiness_deferred_since IS
    'readiness 首次等待的开始时间（原 fire 身份保持不变，仅用于计算等待窗口）。';

COMMENT ON COLUMN batch.scheduled_occurrence.readiness_deadline IS
    'readiness 等待截止时间（deferred_since + readiness-window-seconds）；超窗后由扫尾器终结为 READINESS_TIMEOUT。';
