-- =========================================================
-- V13 - 为文件模板配置增加分块大小
-- 说明：
-- 1) 为 file_template_config.chunk_size 设置数据库默认值。
-- 2) 通过独立 CHECK 约束确保 chunk_size 大于 0。
-- =========================================================

ALTER TABLE batch.file_template_config
    ADD COLUMN IF NOT EXISTS chunk_size INTEGER NOT NULL DEFAULT 500;

ALTER TABLE batch.file_template_config
    DROP CONSTRAINT IF EXISTS ck_file_template_chunk_size;

ALTER TABLE batch.file_template_config
    ADD CONSTRAINT ck_file_template_chunk_size CHECK (chunk_size > 0);
