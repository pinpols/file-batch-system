-- =========================================================
-- V14 - Import preprocess pipeline (ordered plugins, JSON array)
-- 说明：
-- 1) Store preprocess steps as an ordered JSONB array.
-- 2) 以列注释作为支持步骤清单的唯一事实来源。
-- =========================================================

ALTER TABLE batch.file_template_config
    ADD COLUMN IF NOT EXISTS preprocess_pipeline JSONB;

COMMENT ON COLUMN batch.file_template_config.preprocess_pipeline IS
    'Ordered preprocess steps: UNZIP, GUNZIP, AES_GCM_DECRYPT, VERIFY_DIGEST, VERIFY_RSA_SHA256, CHARSET_TRANSCODE, etc.';
