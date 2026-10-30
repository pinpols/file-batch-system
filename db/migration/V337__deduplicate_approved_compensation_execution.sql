-- 为已批准的补偿执行生成请求指纹，阻止同一载荷重复执行。

ALTER TABLE batch.compensation_command
    ADD COLUMN request_fingerprint VARCHAR(64);

ALTER TABLE archive.compensation_command_archive
    ADD COLUMN request_fingerprint VARCHAR(64);

COMMENT ON COLUMN batch.compensation_command.request_fingerprint IS
    '经批准的补偿执行载荷的 SHA-256 摘要；不包含链路标识和认证操作人信息。';

COMMENT ON COLUMN archive.compensation_command_archive.request_fingerprint IS
    '已批准补偿执行载荷的归档 SHA-256 身份摘要。';

CREATE UNIQUE INDEX uk_compensation_command_tenant_approval
    ON batch.compensation_command (tenant_id, approval_id)
    WHERE approval_id IS NOT NULL;

CREATE UNIQUE INDEX uk_compensation_command_archive_tenant_approval
    ON archive.compensation_command_archive (tenant_id, approval_id)
    WHERE approval_id IS NOT NULL;
