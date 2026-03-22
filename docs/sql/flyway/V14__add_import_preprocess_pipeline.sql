-- =========================================================
-- V14 - Import preprocess pipeline (ordered plugins, JSON array)
-- =========================================================

ALTER TABLE batch.file_template_config
    ADD COLUMN IF NOT EXISTS preprocess_pipeline JSONB;

COMMENT ON COLUMN batch.file_template_config.preprocess_pipeline IS
    'Ordered preprocess steps: UNZIP, GUNZIP, AES_GCM_DECRYPT, VERIFY_DIGEST, VERIFY_RSA_SHA256, CHARSET_TRANSCODE, etc.';
