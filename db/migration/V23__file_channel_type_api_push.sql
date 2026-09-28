-- =========================================================
-- V22 - Allow API_PUSH channel type
-- 说明：
-- 1) Extend file_channel_config to support HTTP push delivery.
-- 2) 推送认证请求头和端点配置继续保存在 config_json 中。
-- =========================================================

ALTER TABLE batch.file_channel_config DROP CONSTRAINT IF EXISTS ck_file_channel_type;

ALTER TABLE batch.file_channel_config
    ADD CONSTRAINT ck_file_channel_type CHECK (channel_type IN (
        'SFTP', 'API', 'API_PUSH', 'EMAIL', 'NAS', 'OSS', 'LOCAL'
    ));
