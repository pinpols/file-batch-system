-- =========================================================
-- V13 - Add chunk size to file template config
-- Notes:
-- 1) Keep a database-level default for file_template_config.chunk_size.
-- 2) Enforce chunk_size > 0 through a dedicated check constraint.
-- =========================================================

ALTER TABLE batch.file_template_config
    ADD COLUMN IF NOT EXISTS chunk_size INTEGER NOT NULL DEFAULT 500;

ALTER TABLE batch.file_template_config
    DROP CONSTRAINT IF EXISTS ck_file_template_chunk_size;

ALTER TABLE batch.file_template_config
    ADD CONSTRAINT ck_file_template_chunk_size CHECK (chunk_size > 0);
