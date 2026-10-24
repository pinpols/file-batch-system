-- 收紧 secret_version.secret_payload 约束，拒绝格式字段缺失的 JSONB 对象并校验存量。
--
-- V210 的 JSONB CHECK 对缺少 format/ciphertext 的对象会得到 SQL NULL；PostgreSQL CHECK
-- 仅拒绝 FALSE，因此该对象会被误放行。Flyway 的 SecretPayloadFlywayCallback 会在执行
-- V210/V211 前使用应用 KMS 加密历史行；本迁移负责收紧最终数据库约束并校验存量。

ALTER TABLE batch.secret_version
    DROP CONSTRAINT ck_secret_version_payload_protected;

ALTER TABLE batch.secret_version
    ADD CONSTRAINT ck_secret_version_payload_protected
    CHECK (
        secret_payload IS NULL
        OR (
            jsonb_typeof(secret_payload) = 'object'
            AND secret_payload ? 'format'
            AND secret_payload ->> 'format' = 'BATCHENC_BASE64_V1'
            AND secret_payload ? 'ciphertext'
            AND length(secret_payload ->> 'ciphertext') >= 32
        )
    ) NOT VALID;

ALTER TABLE batch.secret_version
    VALIDATE CONSTRAINT ck_secret_version_payload_protected;
