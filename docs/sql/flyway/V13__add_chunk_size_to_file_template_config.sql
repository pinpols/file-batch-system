-- =========================================================
-- V13 - Add chunk size to file template config
-- =========================================================

ALTER TABLE batch.file_template_config
    ADD COLUMN IF NOT EXISTS chunk_size INTEGER NOT NULL DEFAULT 500;

ALTER TABLE batch.file_template_config
    DROP CONSTRAINT IF EXISTS ck_file_template_chunk_size;

ALTER TABLE batch.file_template_config
    ADD CONSTRAINT ck_file_template_chunk_size CHECK (chunk_size > 0);
