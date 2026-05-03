-- V98: 补 archive.job_instance_archive.calendar_code（ Flyway 已对旧版 V97 打勾时不会重跑 V97 ）
--
-- 背景：若库上 batch.flyway_schema_history 已存在 version=97，且当时执行的脚本仅含 V94 的 data_interval_*，
-- 合并后的 V97 全量脚本不会再执行 → 冷表仍缺 V93 的 calendar_code → ArchiveSchemaDriftCheck 报
-- 「hot has extra [calendar_code]」。
-- 本迁移幂等：ADD COLUMN IF NOT EXISTS。
ALTER TABLE archive.job_instance_archive
    ADD COLUMN IF NOT EXISTS calendar_code VARCHAR(64);

COMMENT ON COLUMN archive.job_instance_archive.calendar_code IS
    'V93/V98 镜像列：创建时日历快照，自 batch.job_instance 归档';
