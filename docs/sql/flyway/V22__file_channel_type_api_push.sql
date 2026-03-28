-- =========================================================
-- V22 - Allow API_PUSH channel type
-- Notes:
-- 1) Extend file_channel_config to support HTTP push delivery.
-- 2) Push auth headers and endpoint settings continue to live in config_json.
-- =========================================================

ALTER TABLE batch.file_channel_config DROP CONSTRAINT IF EXISTS ck_file_channel_type;

ALTER TABLE batch.file_channel_config
    ADD CONSTRAINT ck_file_channel_type CHECK (channel_type IN (
        'SFTP', 'API', 'API_PUSH', 'EMAIL', 'NAS', 'OSS', 'LOCAL'
    ));
