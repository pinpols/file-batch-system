-- =========================================================
-- V184 - ADR-046 文件束聚合 · Phase 3 地基:放宽 job_type 接纳 EXPORT/DISPATCH 束
-- =========================================================
-- 范围(只加不改,不破存量):
--   放宽 job_definition.job_type CHECK,新增 'BUNDLE_EXPORT'(归 BatchType.EXPORT)
--   与 'BUNDLE_DISPATCH'(归 BatchType.DISPATCH)两个束作业类型(见 JobType.java)。
--   单向扩值(新集合 ⊇ 旧),存量数据零影响。
--
--   绑定列(source_file_id / template_code / target_ref)V183 已加,export/dispatch
--   复用同三列(导出绑 template_code、分发绑 source_file_id + target_ref=渠道),
--   无需新增列;archive 镜像 V183 已对齐,本迁移不动 archive。
--
-- 两步走是本仓硬约定(同 V183):
--   * squawk(constraint-missing-not-valid)要求 ADD 带 NOT VALID(避免加约束时全表扫描阻塞写);
--   * NotValidConstraintGuard 启动期 fail-fast 不容 convalidated=false 的悬挂约束 → 必须同迁移补 VALIDATE。
--   本次单向扩值,存量行必然满足,VALIDATE 瞬时通过(job_definition 是小配置表)。
-- =========================================================

ALTER TABLE batch.job_definition DROP CONSTRAINT IF EXISTS ck_job_definition_job_type;
ALTER TABLE batch.job_definition
    ADD CONSTRAINT ck_job_definition_job_type
        CHECK (job_type IN ('GENERAL', 'IMPORT', 'EXPORT', 'PROCESS', 'DISPATCH',
                            'WORKFLOW', 'ATOMIC', 'BUNDLE_IMPORT', 'BUNDLE_EXPORT',
                            'BUNDLE_DISPATCH')) NOT VALID;
ALTER TABLE batch.job_definition VALIDATE CONSTRAINT ck_job_definition_job_type;

COMMENT ON COLUMN batch.job_partition.target_ref IS
    'ADR-046 per-file 绑定:目标引用(导入=目标表/导出=源表查询/分发=下游渠道 channel_code)';
