-- =========================================================
-- V17 - File template security flags and download governance
-- Notes:
-- 1) Add masking, encryption, and approval flags to file_template_config.
-- 2) Keep the detailed semantics on the column comments for console use.
-- =========================================================

ALTER TABLE batch.file_template_config
    ADD COLUMN IF NOT EXISTS preview_masking_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS error_line_masking_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS log_masking_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS content_encryption_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS encryption_key_ref VARCHAR(256),
    ADD COLUMN IF NOT EXISTS download_requires_approval BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS masking_rule_set VARCHAR(128);

COMMENT ON COLUMN batch.file_template_config.preview_masking_enabled IS 'Console preview / sample rows masked';
COMMENT ON COLUMN batch.file_template_config.error_line_masking_enabled IS 'Bad-record / error line storage masked';
COMMENT ON COLUMN batch.file_template_config.log_masking_enabled IS 'Validation / quality log messages masked';
COMMENT ON COLUMN batch.file_template_config.content_encryption_enabled IS 'Object stored with app-level encryption (see encrypt_type + encryption_key_ref)';
COMMENT ON COLUMN batch.file_template_config.encryption_key_ref IS 'Secret ref / KMS key id for encryption';
COMMENT ON COLUMN batch.file_template_config.download_requires_approval IS 'Presigned download requires approval id';
COMMENT ON COLUMN batch.file_template_config.masking_rule_set IS 'Optional named rule set (e.g. PCI_BASIC)';
