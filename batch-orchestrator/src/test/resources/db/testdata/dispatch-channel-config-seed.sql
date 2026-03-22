-- Test seed data: file_channel_config entries covering all channel types
-- Covers: SFTP, API (pull + push), EMAIL, NAS, OSS, LOCAL
-- All records use tenant_id='t1'.

INSERT INTO batch.file_channel_config
  (tenant_id, channel_code, channel_name, channel_type,
   target_endpoint, auth_type, config_json,
   receipt_policy, timeout_seconds, enabled)
VALUES

-- 1. SFTP channel (password auth, upload to remote /data/inbound)
('t1', 'CH-SFTP-INBOUND', 'SFTP Inbound Channel', 'SFTP',
 'sftp://sftp.example.com:22/data/inbound',
 'PASSWORD',
 '{
   "host": "sftp.example.com",
   "port": 22,
   "username": "batch_user",
   "remoteDir": "/data/inbound",
   "strictHostKeyChecking": false,
   "connectTimeoutMs": 5000,
   "readTimeoutMs": 30000
 }'::jsonb,
 'NONE', 60, true),

-- 2. SFTP channel (key-pair auth)
('t1', 'CH-SFTP-KEY', 'SFTP Key-Pair Channel', 'SFTP',
 'sftp://secure-sftp.example.com:22/data/outbound',
 'KEY_PAIR',
 '{
   "host": "secure-sftp.example.com",
   "port": 22,
   "username": "batch_svc",
   "remoteDir": "/data/outbound",
   "privateKeySecretRef": "sftp-private-key-secret",
   "knownHostsSecretRef": "sftp-known-hosts-secret",
   "strictHostKeyChecking": true
 }'::jsonb,
 'NONE', 120, true),

-- 3. API push channel (Bearer token, sync receipt)
('t1', 'CH-API-PUSH', 'API Push Channel', 'API',
 'http://downstream.example.com/api/v1/files/receive',
 'TOKEN',
 '{
   "method": "POST",
   "contentType": "multipart/form-data",
   "tokenSecretRef": "api-downstream-token-secret",
   "retryOnHttpCodes": [502, 503, 504],
   "maxRetries": 3
 }'::jsonb,
 'SYNC', 30, true),

-- 4. API pull channel (OAuth2, polling receipt)
('t1', 'CH-API-PULL', 'API Pull Channel', 'API',
 'http://upstream.example.com/api/v1/files/download',
 'OAUTH2',
 '{
   "method": "GET",
   "oauthTokenUrl": "http://auth.example.com/oauth/token",
   "oauthClientIdSecretRef": "api-oauth-client-id",
   "oauthClientSecretRef": "api-oauth-client-secret",
   "oauthScope": "files:read",
   "fileIdParamName": "fileId"
 }'::jsonb,
 'POLLING', 60, true),

-- 5. EMAIL channel (SMTP, attachment dispatch)
('t1', 'CH-EMAIL-REPORT', 'Email Report Channel', 'EMAIL',
 'smtp://smtp.example.com:587',
 'PASSWORD',
 '{
   "smtpHost": "smtp.example.com",
   "smtpPort": 587,
   "useTLS": true,
   "username": "batch-noreply@example.com",
   "passwordSecretRef": "smtp-password-secret",
   "defaultFrom": "batch-noreply@example.com",
   "defaultTo": ["ops@example.com"],
   "subjectTemplate": "批量文件报告 [{bizDate}] {jobCode}",
   "bodyTemplate": "请见附件。\n\n批次号：{batchNo}\n记录数：{recordCount}",
   "maxAttachmentSizeMb": 10
 }'::jsonb,
 'NONE', 30, true),

-- 6. NAS (network file share) channel
('t1', 'CH-NAS-ARCHIVE', 'NAS Archive Channel', 'NAS',
 '//nas.example.com/batch/archive',
 'NONE',
 '{
   "mountPath": "/mnt/nas/batch/archive",
   "subDirPattern": "{tenantId}/{bizDate}/{jobCode}",
   "createDirIfAbsent": true,
   "filePermission": "640"
 }'::jsonb,
 'NONE', 30, true),

-- 7. OSS (object storage) channel
('t1', 'CH-OSS-EXPORT', 'OSS Export Channel', 'OSS',
 'https://oss.example.com',
 'KEY_PAIR',
 '{
   "endpoint": "https://oss.example.com",
   "bucket": "batch-export",
   "keyPrefix": "{tenantId}/{bizDate}/{jobCode}/",
   "accessKeyIdSecretRef": "oss-access-key-id",
   "accessKeySecretRef": "oss-access-key-secret",
   "region": "cn-hangzhou",
   "storageClass": "STANDARD",
   "expirationDays": 30
 }'::jsonb,
 'ASYNC', 60, true),

-- 8. LOCAL channel (dev/test use only)
('t1', 'CH-LOCAL-TEST', 'Local Filesystem Channel (Test)', 'LOCAL',
 null,
 'NONE',
 '{
   "basePath": "/tmp/batch-test/dispatch",
   "subDirPattern": "{jobCode}/{bizDate}",
   "createDirIfAbsent": true
 }'::jsonb,
 'NONE', 10, true)

ON CONFLICT DO NOTHING;
