-- =========================================================
-- V15：增加导入预处理流水线（按顺序执行插件，采用 JSON 数组）。
-- 说明：
-- 1) 以有序 JSONB 数组保存预处理步骤。
-- 2) 以列注释作为支持步骤清单的唯一事实来源。
-- =========================================================

ALTER TABLE batch.file_template_config
    ADD COLUMN IF NOT EXISTS preprocess_pipeline JSONB;

COMMENT ON COLUMN batch.file_template_config.preprocess_pipeline IS
    '按顺序执行的预处理步骤，例如 UNZIP、GUNZIP、AES_GCM_DECRYPT、VERIFY_DIGEST、VERIFY_RSA_SHA256、CHARSET_TRANSCODE 等。';
