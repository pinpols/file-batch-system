-- 新库配置包直接提供规范字段；这里只覆盖当前运行环境的路由与公钥路径。
-- 锁定完整的三通道集合，任何缺失、旧别名或部分更新都使准备事务失败。
\set ON_ERROR_STOP on
BEGIN;

WITH owned_fixtures AS MATERIALIZED (
    SELECT id, config_json
    FROM batch.file_channel_config
    WHERE channel_type = 'SFTP'
      AND (tenant_id, channel_code) IN (('ta', 'ta_sftp_inbound'), ('tb', 'tb_sftp_inbound'), ('tc', 'tc_sftp_score'))
    FOR UPDATE
), valid_scope AS (
    SELECT count(*) = 3 AND bool_and(
        jsonb_typeof(config_json->'sftp_user') = 'string'
        AND nullif(btrim(config_json->>'sftp_user'), '') IS NOT NULL
        AND jsonb_typeof(config_json->'sftp_remote_directory') = 'string'
        AND nullif(btrim(config_json->>'sftp_remote_directory'), '') IS NOT NULL
        AND NOT config_json ?| ARRAY[
            'host', 'sftpHost', 'port', 'sftpPort', 'username', 'sftpUsername', 'sftp_username',
            'password', 'sftpPassword', 'remotePath', 'sftpRemotePath', 'remote_path', 'sftp_remote_path'
        ]
    ) AS valid
    FROM owned_fixtures
), prepared AS (
    UPDATE batch.file_channel_config AS channel
    SET config_json = owned.config_json || jsonb_build_object(
        'sftp_host', :'sftp_host', 'sftp_port', :'sftp_port'::integer,
        'sftp_strict_host_key_checking', 'yes', 'sftp_known_hosts_path', :'known_hosts_path'),
        updated_at = current_timestamp
    FROM owned_fixtures AS owned, valid_scope
    WHERE channel.id = owned.id AND valid_scope.valid
    RETURNING channel.id
)
SELECT count(*) = 3 AS sftp_probe_fixtures_ready FROM prepared
\gset

\if :sftp_probe_fixtures_ready
COMMIT;
\else
DO $$
BEGIN
    RAISE EXCEPTION 'canonical SFTP fixture requires all three owned SFTP channels, nonempty string sftp_user/sftp_remote_directory, and no legacy aliases'
        USING ERRCODE = '22023';
END;
$$;
\endif
