-- =========================================================
-- V23：允许通道类型使用 API_PUSH。
-- 说明：
-- 1) 扩展 file_channel_config，以支持 HTTP 推送投递。
-- 2) 推送认证请求头和端点配置继续保存在 config_json 中。
-- =========================================================

ALTER TABLE batch.file_channel_config DROP CONSTRAINT IF EXISTS ck_file_channel_type;

ALTER TABLE batch.file_channel_config
    ADD CONSTRAINT ck_file_channel_type CHECK (channel_type IN (
        'SFTP', 'API', 'API_PUSH', 'EMAIL', 'NAS', 'OSS', 'LOCAL'
    ));
