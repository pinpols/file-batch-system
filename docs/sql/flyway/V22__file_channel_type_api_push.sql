-- Allow API_PUSH channel type (HTTP push with auth headers from config_json).

ALTER TABLE batch.file_channel_config DROP CONSTRAINT IF EXISTS ck_file_channel_type;

ALTER TABLE batch.file_channel_config
    ADD CONSTRAINT ck_file_channel_type CHECK (channel_type IN (
        'SFTP', 'API', 'API_PUSH', 'EMAIL', 'NAS', 'OSS', 'LOCAL'
    ));
