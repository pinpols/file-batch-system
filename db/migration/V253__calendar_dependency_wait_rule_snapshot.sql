-- =====================================================================
-- V253：保存日历日期首次进入等待时观察到的依赖规则。
-- 解除等待时仍按当前启用的配置进行判定，方便运维人员
-- 修复依赖定义后由协调器释放等待。
-- =====================================================================

ALTER TABLE batch.calendar_dependency_wait
    ADD COLUMN IF NOT EXISTS dependency_snapshot JSONB;

COMMENT ON COLUMN batch.calendar_dependency_wait.dependency_snapshot IS
    '首次等待时观察到的上游 calendar 与 rule 快照，仅用于审计；重检按当前启用配置裁决，允许修复配置后自动释放';
