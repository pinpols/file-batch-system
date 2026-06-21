-- =========================================================
-- V183 - ADR-046 文件束聚合 · Phase 1 第一刀:纯加法数据地基
-- =========================================================
-- 范围(只加不改,不破存量):
--   1) 放宽 job_definition.job_type CHECK,新增 'BUNDLE_IMPORT'(束作业类型,
--      归 BatchType.IMPORT 桶;见 JobType.java)。单向扩值,存量数据零影响。
--   2) job_partition 加 per-file 绑定列 source_file_id / template_code /
--      target_ref(可空,无默认)。第一刀仅加列、不写入;第二刀(launch 按
--      manifest 展异构 partition)才赋值。
--   3) archive.job_partition_archive 同步加同 3 列(ArchiveSchemaDriftCheck
--      启动期 1:1 fail-fast 兜底,见 V71/V95/V117 镜像惯例)。
-- 不动(留给后续刀):UNIQUE/PK 约束、FK(source_file_id→file_upload_info)、
--   partition 创建逻辑、claim/report、job_task.task_type。
-- =========================================================

-- 1) job_type CHECK 放宽(DROP IF EXISTS → ADD NOT VALID → 立即 VALIDATE)。
-- 两步走是本仓硬约定:
--   * squawk(constraint-missing-not-valid)要求 ADD 带 NOT VALID(避免加约束时全表扫描阻塞写);
--   * NotValidConstraintGuard 启动期 fail-fast 不容 convalidated=false 的悬挂约束 → 必须同迁移补 VALIDATE。
-- 本次是单向扩值(新集合 ⊇ 旧),存量行必然满足,VALIDATE 瞬时通过(job_definition 是小配置表)。
ALTER TABLE batch.job_definition DROP CONSTRAINT IF EXISTS ck_job_definition_job_type;
ALTER TABLE batch.job_definition
    ADD CONSTRAINT ck_job_definition_job_type
        CHECK (job_type IN ('GENERAL', 'IMPORT', 'EXPORT', 'PROCESS', 'DISPATCH',
                            'WORKFLOW', 'ATOMIC', 'BUNDLE_IMPORT')) NOT VALID;
ALTER TABLE batch.job_definition VALIDATE CONSTRAINT ck_job_definition_job_type;

-- 2) job_partition per-file 绑定列(热表)
ALTER TABLE batch.job_partition
    ADD COLUMN IF NOT EXISTS source_file_id BIGINT;
ALTER TABLE batch.job_partition
    ADD COLUMN IF NOT EXISTS template_code VARCHAR(128);
ALTER TABLE batch.job_partition
    ADD COLUMN IF NOT EXISTS target_ref VARCHAR(256);

COMMENT ON COLUMN batch.job_partition.source_file_id IS
    'ADR-046 per-file 绑定:源文件 id(束内每 partition 绑一个文件);第二刀写入';
COMMENT ON COLUMN batch.job_partition.template_code IS
    'ADR-046 per-file 绑定:该 partition 用的文件模板 code(异构束内各 partition 可不同)';
COMMENT ON COLUMN batch.job_partition.target_ref IS
    'ADR-046 per-file 绑定:目标引用(导入=目标表/导出=源表查询/分发=下游渠道)';

-- 3) archive 冷表镜像(1:1,ArchiveSchemaDriftCheck 守护)
ALTER TABLE archive.job_partition_archive
    ADD COLUMN IF NOT EXISTS source_file_id BIGINT;
ALTER TABLE archive.job_partition_archive
    ADD COLUMN IF NOT EXISTS template_code VARCHAR(128);
ALTER TABLE archive.job_partition_archive
    ADD COLUMN IF NOT EXISTS target_ref VARCHAR(256);
