-- =========================================================
-- V18 - 文件模板安全标记与下载治理
-- 说明：
-- 1) 为 file_template_config 增加脱敏、加密和审批标记。
-- 2) 将字段详细语义写入列注释，供控制台展示。
-- =========================================================

ALTER TABLE batch.file_template_config
    ADD COLUMN IF NOT EXISTS preview_masking_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS error_line_masking_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS log_masking_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS content_encryption_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS encryption_key_ref VARCHAR(256),
    ADD COLUMN IF NOT EXISTS download_requires_approval BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS masking_rule_set VARCHAR(128);

COMMENT ON COLUMN batch.file_template_config.preview_masking_enabled IS 'Console 预览和样例行的脱敏开关。';
COMMENT ON COLUMN batch.file_template_config.error_line_masking_enabled IS '坏记录和错误行持久化时的脱敏开关。';
COMMENT ON COLUMN batch.file_template_config.log_masking_enabled IS '校验和质量检查日志消息的脱敏开关。';
COMMENT ON COLUMN batch.file_template_config.content_encryption_enabled IS '对象是否使用应用层加密存储；加密类型和密钥引用见 encrypt_type 与 encryption_key_ref。';
COMMENT ON COLUMN batch.file_template_config.encryption_key_ref IS '加密使用的密钥引用或 KMS 密钥 ID。';
COMMENT ON COLUMN batch.file_template_config.download_requires_approval IS '预签名下载所需的审批记录 ID。';
COMMENT ON COLUMN batch.file_template_config.masking_rule_set IS '可选的命名脱敏规则集，例如 PCI_BASIC。';
