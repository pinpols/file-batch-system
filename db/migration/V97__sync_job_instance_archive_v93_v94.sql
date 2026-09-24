-- V97: archive.job_instance_archive 一次性对齐 batch.job_instance（V93 calendar_code + V94 data_interval_*）
--
-- CLAUDE.md archive 冷表对齐：热表新增列必须同 PR 补 archive 镜像，否则 ArchiveSchemaDriftCheck 启动 fail-fast。
-- 冷库仅列对齐 + COMMENT；CHECK 留在热表。
--
-- 全新库：本脚本与 batch.job_instance（V93/V94）对齐。
-- 若库上曾执行过「仅含 data_interval_*」的旧 V97，Flyway 不会重跑本版本 → 依赖 **V98** 补 calendar_code。
ALTER TABLE archive.job_instance_archive
    ADD COLUMN IF NOT EXISTS calendar_code VARCHAR(64);

ALTER TABLE archive.job_instance_archive
    ADD COLUMN IF NOT EXISTS data_interval_start TIMESTAMPTZ;

ALTER TABLE archive.job_instance_archive
    ADD COLUMN IF NOT EXISTS data_interval_end TIMESTAMPTZ;

COMMENT ON COLUMN archive.job_instance_archive.calendar_code IS
    'V93/V97 镜像列：创建时日历快照，自 batch.job_instance 归档';

COMMENT ON COLUMN archive.job_instance_archive.data_interval_start IS
    'V94/V97 镜像列：半开区间起点，自 batch.job_instance 归档';

COMMENT ON COLUMN archive.job_instance_archive.data_interval_end IS
    'V94/V97 镜像列：半开区间终点，自 batch.job_instance 归档';
